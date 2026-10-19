-- Public profiles: a picture, a unique @handle, a short bio, a home town, and a link.
--
-- The owner is the only person who can change their row (profiles_update_own).
-- tier, role, and points stay server-managed. Demo accounts are unchanged:
-- the new columns are optional, and is_demo is still not a client column.
--
-- Avatars live in a public Storage bucket. The owner writes only inside their
-- own folder. Anyone can read. The bucket caps each file at 512 KB.

alter table public.profiles
  add column bio text,
  add column website text;

alter table public.profiles
  add constraint profiles_bio_length check (bio is null or char_length(bio) between 1 and 160),
  add constraint profiles_website_length check (website is null or char_length(website) between 1 and 200),
  add constraint profiles_website_format check (
    website is null or website ~* '^https?://[^[:space:]]+$'
  );

comment on table public.profiles is
  'One row per auth user. tier/role/points are server-managed. The owner may edit handle, display_name, avatar_path, bio, home_area, and website.';
comment on column public.profiles.bio is 'Public bio, at most 160 characters.';
comment on column public.profiles.home_area is 'Optional home town or region the person chose to show.';
comment on column public.profiles.website is 'Optional public http or https link.';

grant update (bio, website) on public.profiles to authenticated;

-- ---------------------------------------------------------------------------
-- The name, handle, bio, town, link, and avatar the app saves together.
-- A blank optional field clears it. The handle is stored lowercase.
-- ---------------------------------------------------------------------------
create function public.update_my_profile(
  p_display_name text,
  p_handle text,
  p_bio text default null,
  p_home_area text default null,
  p_website text default null,
  p_avatar_path text default null
)
returns public.profiles
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_name text := btrim(p_display_name);
  v_handle text := lower(btrim(p_handle));
  v_bio text := nullif(btrim(p_bio), '');
  v_home text := nullif(btrim(p_home_area), '');
  v_site text := nullif(btrim(p_website), '');
  v_avatar text := nullif(btrim(p_avatar_path), '');
  v_profile public.profiles;
  v_current text;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  if v_name is null or char_length(v_name) not between 1 and 50 or v_name ~ '[[:cntrl:]]' then
    raise exception 'invalid_display_name' using errcode = '22023';
  end if;
  if v_handle is null or v_handle !~ '^[a-z0-9_]{3,24}$' then
    raise exception 'invalid_handle' using errcode = '22023';
  end if;
  if v_bio is not null and (char_length(v_bio) > 160 or v_bio ~ '[[:cntrl:]]') then
    raise exception 'invalid_bio' using errcode = '22023';
  end if;
  if v_home is not null and (char_length(v_home) > 80 or v_home ~ '[[:cntrl:]]') then
    raise exception 'invalid_home_area' using errcode = '22023';
  end if;
  if v_site is not null and (
    char_length(v_site) > 200 or v_site !~* '^https?://[^[:space:]]+$'
  ) then
    raise exception 'invalid_website' using errcode = '22023';
  end if;
  select p.avatar_path into v_current
  from public.profiles p
  where p.id = v_uid and p.deleted_at is null;
  if not found then
    raise exception 'account_unavailable' using errcode = '42501';
  end if;
  if v_avatar is not null
     and v_avatar is distinct from v_current
     and v_avatar !~ ('^' || v_uid::text || '/[0-9a-f-]{36}\.(webp|jpe?g)$') then
    raise exception 'invalid_avatar' using errcode = '22023';
  end if;

  begin
    update public.profiles
    set display_name = v_name,
        handle = v_handle,
        bio = v_bio,
        home_area = v_home,
        website = v_site,
        avatar_path = v_avatar
    where id = v_uid and deleted_at is null
    returning * into v_profile;
    if not found then
      raise exception 'account_unavailable' using errcode = '42501';
    end if;
  exception
    when unique_violation then
      raise exception 'handle_taken' using errcode = '23505';
  end;

  return v_profile;
end;
$$;

-- Case-insensitive. True for the caller's own handle. False when the
-- spelling is invalid or another account already has it.
create function public.handle_available(p_handle text)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select auth.uid() is not null
    and lower(btrim(coalesce(p_handle, ''))) ~ '^[a-z0-9_]{3,24}$'
    and not exists (
      select 1
      from public.profiles p
      where p.handle = lower(btrim(p_handle))
        and p.id is distinct from auth.uid()
    );
$$;

-- What a signed-in person sees when they open an author. Role, points, and
-- moderation stay off this payload. Anonymous drops are not "public drops".
drop function public.public_profile(text);

create function public.public_profile(p_handle text)
returns table (
  id uuid,
  handle text,
  display_name text,
  created_at timestamptz,
  avatar_path text,
  bio text,
  home_area text,
  website text,
  tier public.user_tier,
  drop_count integer,
  badges jsonb,
  recent_drops jsonb
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_profile public.profiles;
  v_handle text := lower(btrim(coalesce(p_handle, '')));
begin
  if auth.uid() is null or v_handle = '' then
    return;
  end if;
  select * into v_profile
  from public.profiles p
  where p.handle = v_handle and p.deleted_at is null;
  if not found then
    return;
  end if;

  return query
  select
    v_profile.id,
    v_profile.handle,
    v_profile.display_name,
    v_profile.created_at,
    v_profile.avatar_path,
    v_profile.bio,
    v_profile.home_area,
    v_profile.website,
    v_profile.tier,
    (
      select count(*)::integer
      from public.drops d
      where d.author_id = v_profile.id
        and d.source = 'user'
        and d.deleted_at is null
        and d.status in ('active', 'confirmed')
        and not d.display_anonymous
    ),
    coalesce((
      select jsonb_agg(jsonb_build_object(
        'slug', b.slug,
        'name', b.name,
        'icon', b.icon,
        'description', b.description,
        'region_id', ub.region_id,
        'region_name', r.name,
        'rank', nullif(ub.meta ->> 'rank', '')::integer,
        'awarded_at', ub.awarded_at
      ) order by ub.awarded_at, b.sort, b.slug)
      from public.user_badges ub
      join public.badges b on b.id = ub.badge_id and b.active
      left join public.regions r on r.id = ub.region_id
      where ub.user_id = v_profile.id
    ), '[]'::jsonb),
    coalesce((
      select jsonb_agg(jsonb_build_object(
        'id', s.id,
        'short_id', s.short_id,
        'title', s.title,
        'category_slug', s.slug,
        'created_at', s.created_at
      ) order by s.created_at desc)
      from (
        select d.id, d.short_id, d.title, c.slug, d.created_at
        from public.drops d
        join public.categories c on c.id = d.category_id
        where d.author_id = v_profile.id
          and d.source = 'user'
          and d.deleted_at is null
          and d.status in ('active', 'confirmed')
          and not d.display_anonymous
        order by d.created_at desc
        limit 12
      ) s
    ), '[]'::jsonb);
end;
$$;

revoke execute on function
  public.update_my_profile(text, text, text, text, text, text),
  public.handle_available(text),
  public.public_profile(text)
from public, anon, authenticated;

grant execute on function
  public.update_my_profile(text, text, text, text, text, text),
  public.handle_available(text),
  public.public_profile(text)
to authenticated, service_role;

-- The picture shown next to the name on a drop.
alter type public.drop_card add attribute author_avatar_path text;

do $avatar$
declare
  v_sql text := pg_get_functiondef('private.to_drop_card(public.drops, uuid, extensions.geography)'::regprocedure);
  v_old constant text := $old$select p.handle, nullif(btrim(p.display_name), '')
      into v_card.author_handle, v_card.author_display_name
    from public.profiles p where p.id = p_drop.author_id;$old$;
  v_new constant text := $new$select p.handle, nullif(btrim(p.display_name), ''), nullif(btrim(p.avatar_path), '')
      into v_card.author_handle, v_card.author_display_name, v_card.author_avatar_path
    from public.profiles p where p.id = p_drop.author_id;$new$;
begin
  if position('author_avatar_path' in v_sql) > 0 then
    return;
  end if;
  if position(v_old in v_sql) = 0 then
    raise exception 'to_drop_card avatar anchor missing';
  end if;
  execute replace(v_sql, v_old, v_new);
end;
$avatar$;

-- A face on the board. No handle and no user id. A missing picture is omitted
-- so older board JSON stays the same.
do $board$
declare
  v_sql text := pg_get_functiondef('private.campaign_json(uuid, uuid)'::regprocedure);
  v_old constant text := $old$select coalesce(jsonb_agg(jsonb_build_object(
    'display_name', s.display_name,
    'drops', s.drops
  ) order by s.drops desc, s.display_name, s.author_id), '[]'::jsonb)
  into v_board
  from (
    select p.display_name, p.id as author_id, count(*)::integer as drops
    from public.drops d
    join public.profiles p on p.id = d.author_id
    where private.drop_counts_in_region(d, v_region, v_campaign.starts_at, v_campaign.ends_at)
      and p.show_on_leaderboard
      and p.display_name is not null
      and btrim(p.display_name) <> ''
    group by p.id, p.display_name$old$;
  v_new constant text := $new$select coalesce(jsonb_agg(jsonb_strip_nulls(jsonb_build_object(
    'display_name', s.display_name,
    'avatar_path', nullif(btrim(s.avatar_path), ''),
    'drops', s.drops
  )) order by s.drops desc, s.display_name, s.author_id), '[]'::jsonb)
  into v_board
  from (
    select p.display_name, p.avatar_path, p.id as author_id, count(*)::integer as drops
    from public.drops d
    join public.profiles p on p.id = d.author_id
    where private.drop_counts_in_region(d, v_region, v_campaign.starts_at, v_campaign.ends_at)
      and p.show_on_leaderboard
      and p.display_name is not null
      and btrim(p.display_name) <> ''
    group by p.id, p.display_name, p.avatar_path$new$;
begin
  if position('avatar_path' in v_sql) > 0 then
    return;
  end if;
  if position(v_old in v_sql) = 0 then
    raise exception 'campaign_json avatar anchor missing';
  end if;
  execute replace(v_sql, v_old, v_new);
end;
$board$;

do $export$
declare
  v_sql text := pg_get_functiondef('public.export_my_data()'::regprocedure);
  v_old constant text := $old$'avatar_path', v_profile.avatar_path,$old$;
  v_new constant text := $new$'avatar_path', v_profile.avatar_path,
      'bio', v_profile.bio,
      'website', v_profile.website,$new$;
begin
  if position('''bio'', v_profile.bio' in v_sql) > 0 then
    return;
  end if;
  if position(v_old in v_sql) = 0 then
    raise exception 'export_my_data profile anchor missing';
  end if;
  execute replace(v_sql, v_old, v_new);
end;
$export$;

-- ---------------------------------------------------------------------------
-- avatars: public read, owner write, 512 KB, jpeg or webp.
-- ---------------------------------------------------------------------------
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
  'avatars',
  'avatars',
  true,
  524288,
  array['image/jpeg', 'image/webp']
)
on conflict (id) do update
  set public = excluded.public,
      file_size_limit = excluded.file_size_limit,
      allowed_mime_types = excluded.allowed_mime_types;

create policy avatars_select_public on storage.objects
  for select to anon, authenticated
  using (bucket_id = 'avatars');

create policy avatars_insert_own on storage.objects
  for insert to authenticated
  with check (
    bucket_id = 'avatars'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  );

create policy avatars_update_own on storage.objects
  for update to authenticated
  using (
    bucket_id = 'avatars'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  )
  with check (
    bucket_id = 'avatars'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  );

create policy avatars_delete_own on storage.objects
  for delete to authenticated
  using (
    bucket_id = 'avatars'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  );
