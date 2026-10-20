-- Community contributions (PLAN §0 Waze-fast contribution, PLAN §3 place layers, PLAN §4 allow_anonymous_display).
--
-- A photo, a short video, or a line of text can be attached to any place:
-- a user drop, a historical marker, an open-data place, a voice object, or a
-- civic item. The target is (target_kind, target_id). Region is not stored.
--
-- The author is always recorded. When they post anonymously, public RPCs
-- return no handle and no author id. Moderators read the author through
-- contribution_author. Storage paths are c/<contribution id>/<media id>.<ext>
-- and never the author's id. A trigger clears storage.objects.owner on this
-- bucket so a list response cannot leak the uploader.
--
-- The bucket stays private. Signing is a SELECT, so contribution_media_select_visible
-- has to exist or Storage answers 400 not_found (the drop-media lesson).
-- The helper is security definer because contributions are not readable as
-- the caller in a way that exposes author_id, and a policy that joined the
-- table as the caller would hide every other person's file.

alter table public.tier_limits
  add column contributions_per_day integer not null default 20
    check (contributions_per_day >= 0);

update public.tier_limits
set contributions_per_day = case tier
  when 'new' then 10
  when 'regular' then 30
  when 'trusted' then 60
  when 'guide' then 120
  else contributions_per_day
end;

comment on column public.tier_limits.contributions_per_day is
  'How many community contributions this tier may add in a rolling day.';

-- ---------------------------------------------------------------------------
-- Rows
-- ---------------------------------------------------------------------------
create table public.contributions (
  id uuid primary key default gen_random_uuid(),
  target_kind text not null check (target_kind in ('drop', 'marker', 'open_data', 'thing', 'civic')),
  target_id uuid not null,
  author_id uuid not null references public.profiles (id) on delete cascade,
  display_anonymous boolean not null default false,
  kind text not null check (kind in ('text', 'photo', 'video')),
  body text check (body is null or char_length(btrim(body)) between 1 and 500),
  status text not null default 'active' check (status in ('active', 'pending_review', 'hidden', 'removed')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint contributions_text_has_body check (kind <> 'text' or body is not null)
);

comment on table public.contributions is
  'A photo, video, or note on a place. author_id is for moderators. Public reads go through contributions_for, which omits it when the post is anonymous.';

comment on column public.contributions.author_id is
  'Always stored. Not granted to anon or authenticated. contribution_author returns it to moderators only.';

comment on column public.contributions.display_anonymous is
  'Author asked to hide their name. Refused when the place category sets allow_anonymous_display false (sales).';

alter table public.moderation_actions drop constraint moderation_actions_target_type_check;
alter table public.moderation_actions add constraint moderation_actions_target_type_check
  check (target_type in ('drop', 'profile', 'report', 'category', 'contribution'));

create index contributions_place_idx
  on public.contributions (target_kind, target_id, created_at desc);

create index contributions_author_idx
  on public.contributions (author_id, created_at desc);

create index contributions_live_idx
  on public.contributions (created_at desc)
  where status = 'active' and kind in ('photo', 'video');

create trigger contributions_set_updated_at before update on public.contributions
  for each row execute function private.set_updated_at();

create table public.contribution_media (
  id uuid primary key default gen_random_uuid(),
  contribution_id uuid not null references public.contributions (id) on delete cascade,
  storage_bucket text not null default 'contribution-media' check (storage_bucket = 'contribution-media'),
  storage_path text not null check (
    storage_path ~ '^c/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(jpg|png|webp|mp4|webm)$'
  ),
  thumb_path text check (
    thumb_path is null or thumb_path ~ '^c/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.thumb\.jpg$'
  ),
  mime_type text not null check (mime_type in ('image/jpeg', 'image/png', 'image/webp', 'video/mp4', 'video/webm')),
  byte_size integer not null check (byte_size > 0 and byte_size <= 8388608),
  width integer check (width is null or width between 1 and 1600),
  height integer check (height is null or height between 1 and 1600),
  duration_s numeric(5, 2) check (duration_s is null or (duration_s > 0 and duration_s <= 30)),
  alt_text text check (alt_text is null or char_length(alt_text) <= 300),
  unique (storage_bucket, storage_path),
  unique (contribution_id)
);

comment on table public.contribution_media is
  'One file for a photo or video contribution. The path is two random ids, never the author id.';

create index contribution_media_thumb_idx
  on public.contribution_media (storage_bucket, thumb_path)
  where thumb_path is not null;

create table public.contribution_votes (
  contribution_id uuid not null references public.contributions (id) on delete cascade,
  voter_id uuid not null references public.profiles (id) on delete cascade,
  value smallint not null check (value in (-1, 1)),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (contribution_id, voter_id)
);

comment on table public.contribution_votes is
  'One quality vote per person on a photo or video. The primary key is the unique constraint. The voter cannot be the author.';

create index contribution_votes_voter_idx
  on public.contribution_votes (voter_id, created_at desc);

create trigger contribution_votes_set_updated_at before update on public.contribution_votes
  for each row execute function private.set_updated_at();

-- ---------------------------------------------------------------------------
-- Place lookup. Unknown kinds are not visible, so a new place table is a
-- later migration rather than a free-form string.
-- ---------------------------------------------------------------------------
create function private.contribution_target_visible(p_kind text, p_id uuid, p_viewer uuid)
returns boolean
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_drop public.drops;
  v_thing public.things;
  v_civic public.civic_items;
begin
  if p_kind in ('drop', 'marker') then
    select * into v_drop from public.drops d where d.id = p_id;
    if not found then
      return false;
    end if;
    if p_kind = 'marker' and not exists (
      select 1 from public.categories c
      where c.id = v_drop.category_id and c.slug = 'historical_marker'
    ) then
      return false;
    end if;
    return private.can_view_drop(v_drop, p_viewer);
  elsif p_kind = 'open_data' then
    select d.* into v_drop
    from public.open_data_items i
    join public.drops d on d.id = i.drop_id
    where i.id = p_id;
    if not found then
      return false;
    end if;
    return private.can_view_drop(v_drop, p_viewer);
  elsif p_kind = 'thing' then
    select * into v_thing from public.things t where t.id = p_id;
    if not found or v_thing.status <> 'active' then
      return false;
    end if;
    if v_thing.visibility = 'public' then
      return true;
    end if;
    return p_viewer is not null and v_thing.owner_id = p_viewer;
  elsif p_kind = 'civic' then
    select * into v_civic from public.civic_items i where i.id = p_id;
    if not found or v_civic.status <> 'active' then
      return false;
    end if;
    if v_civic.expires_at is not null and v_civic.expires_at <= now() then
      return false;
    end if;
    return true;
  end if;
  return false;
end;
$$;

comment on function private.contribution_target_visible(text, uuid, uuid) is
  'True when this viewer may see the place a contribution is attached to.';

create function private.anonymous_display_allowed(p_kind text, p_id uuid)
returns boolean
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_allowed boolean;
begin
  if p_kind in ('drop', 'marker') then
    select c.allow_anonymous_display into v_allowed
    from public.drops d
    join public.categories c on c.id = d.category_id
    where d.id = p_id;
    return coalesce(v_allowed, false);
  elsif p_kind = 'open_data' then
    select c.allow_anonymous_display into v_allowed
    from public.open_data_items i
    join public.drops d on d.id = i.drop_id
    join public.categories c on c.id = d.category_id
    where i.id = p_id;
    return coalesce(v_allowed, false);
  elsif p_kind = 'thing' then
    select coalesce(c.allow_anonymous_display, true) into v_allowed
    from public.things t
    left join public.categories c on c.slug = t.category_slug
    where t.id = p_id;
    if not found then
      return false;
    end if;
    return coalesce(v_allowed, true);
  elsif p_kind = 'civic' then
    return exists (
      select 1 from public.civic_items i
      where i.id = p_id and i.status = 'active'
    );
  end if;
  return false;
end;
$$;

comment on function private.anonymous_display_allowed(text, uuid) is
  'Category allow_anonymous_display. Sales stay named. Civic items and uncategorized things may be anonymous.';

create function private.contribution_point(p_kind text, p_id uuid)
returns extensions.geography
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_geog extensions.geography;
begin
  if p_kind in ('drop', 'marker') then
    select d.display_geog into v_geog from public.drops d where d.id = p_id;
  elsif p_kind = 'open_data' then
    select d.display_geog into v_geog
    from public.open_data_items i
    join public.drops d on d.id = i.drop_id
    where i.id = p_id;
  elsif p_kind = 'thing' then
    select t.fixed_geog into v_geog from public.things t where t.id = p_id;
  elsif p_kind = 'civic' then
    select i.geom into v_geog from public.civic_items i where i.id = p_id;
  end if;
  return v_geog;
end;
$$;

create function private.wilson_lower(p_up integer, p_down integer)
returns double precision
language sql
immutable
set search_path = ''
as $$
  select case
    when coalesce(p_up, 0) + coalesce(p_down, 0) <= 0 then 0::double precision
    else greatest(0::double precision, (
      (p_up::double precision / n) + (z * z) / (2 * n)
      - z * sqrt(((p_up::double precision / n) * (1 - p_up::double precision / n) + (z * z) / (4 * n)) / n)
    ) / (1 + (z * z) / n))
  end
  from (
    select (coalesce(p_up, 0) + coalesce(p_down, 0))::double precision as n, 1.96::double precision as z
  ) s
$$;

comment on function private.wilson_lower(integer, integer) is
  'Lower bound of the Wilson score for up votes versus down votes. Zero when nobody has voted.';

create function private.contribution_collapsed(p_up integer, p_down integer)
returns boolean
language sql
immutable
set search_path = ''
as $$
  select coalesce(p_up, 0) + coalesce(p_down, 0) >= 3
    and coalesce(p_down, 0) > coalesce(p_up, 0)
    and private.wilson_lower(coalesce(p_up, 0), coalesce(p_down, 0)) < 0.2
$$;

create function private.contribution_readable_id(p_id uuid, p_viewer uuid)
returns boolean
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_row public.contributions;
begin
  select * into v_row from public.contributions c where c.id = p_id;
  if not found or v_row.status = 'removed' then
    return false;
  end if;
  if p_viewer is not null and v_row.author_id = p_viewer then
    return v_row.status in ('active', 'pending_review', 'hidden');
  end if;
  if v_row.status <> 'active' then
    return false;
  end if;
  if exists (
    select 1 from public.profiles p
    where p.id = v_row.author_id and p.deleted_at is not null
  ) then
    return false;
  end if;
  if p_viewer is not null and private.is_blocked_between(p_viewer, v_row.author_id) then
    return false;
  end if;
  return private.contribution_target_visible(v_row.target_kind, v_row.target_id, p_viewer);
end;
$$;

create function private.contribution_extension(p_mime text)
returns text
language sql
immutable
set search_path = ''
as $$
  select case p_mime
    when 'image/jpeg' then 'jpg'
    when 'image/png' then 'png'
    when 'image/webp' then 'webp'
    when 'video/mp4' then 'mp4'
    when 'video/webm' then 'webm'
    else null
  end
$$;

-- ---------------------------------------------------------------------------
-- Storage. Private bucket. SELECT policy is what makes createSignedUrl work.
-- ---------------------------------------------------------------------------
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
  'contribution-media',
  'contribution-media',
  false,
  8388608,
  array['image/jpeg', 'image/png', 'image/webp', 'video/mp4', 'video/webm']
);

create function private.contribution_objects_hide_owner()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.bucket_id = 'contribution-media' then
    new.owner := null;
    new.owner_id := null;
  end if;
  return new;
end;
$$;

comment on function private.contribution_objects_hide_owner() is
  'Contribution files are not stored under a user id, and the object row does not keep one either.';

create trigger contribution_objects_hide_owner
  before insert or update on storage.objects
  for each row execute function private.contribution_objects_hide_owner();

create function private.can_read_contribution_media(p_bucket text, p_name text)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  with viewer as (
    select (select auth.uid()) as uid
  )
  select
    (select v.uid from viewer v) is not null
    and exists (
      select 1
      from public.contribution_media m
      cross join viewer v
      where m.storage_bucket = p_bucket
        and (m.storage_path = p_name or m.thumb_path = p_name)
        and private.contribution_readable_id(m.contribution_id, v.uid)
    )
$$;

comment on function private.can_read_contribution_media(text, text) is
  'True when the signed-in caller may sign this contribution object. Removed and other people''s pending files are hidden.';

revoke all on function private.can_read_contribution_media(text, text) from public, anon;
grant execute on function private.can_read_contribution_media(text, text) to authenticated;

create function private.can_write_contribution_media(p_bucket text, p_name text)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public.contribution_media m
    join public.contributions c on c.id = m.contribution_id
    where m.storage_bucket = p_bucket
      and (m.storage_path = p_name or m.thumb_path = p_name)
      and c.author_id = (select auth.uid())
      and c.status in ('active', 'pending_review')
      and c.created_at > now() - interval '2 hours'
  )
$$;

comment on function private.can_write_contribution_media(text, text) is
  'True when this path was reserved for the signed-in author in the last two hours.';

revoke all on function private.can_write_contribution_media(text, text) from public, anon;
grant execute on function private.can_write_contribution_media(text, text) to authenticated;

create policy contribution_media_select_visible on storage.objects
  for select to authenticated
  using (
    bucket_id = 'contribution-media'
    and (select private.can_read_contribution_media(bucket_id, name))
  );

comment on policy contribution_media_select_visible on storage.objects is
  'Signed-in read of a visible contribution file. The bucket stays private.';

create policy contribution_media_insert_reserved on storage.objects
  for insert to authenticated
  with check (
    bucket_id = 'contribution-media'
    and (select private.can_write_contribution_media(bucket_id, name))
  );

comment on policy contribution_media_insert_reserved on storage.objects is
  'Upload only to a path create_contribution reserved for this author.';

-- ---------------------------------------------------------------------------
-- Votes. RLS plus the primary key. A trigger blocks a self vote even when
-- the writer is security definer.
-- ---------------------------------------------------------------------------
create function private.contribution_votes_guard()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_author uuid;
  v_kind text;
  v_status text;
begin
  select c.author_id, c.kind, c.status into v_author, v_kind, v_status
  from public.contributions c
  where c.id = new.contribution_id;
  if v_author is null then
    raise exception 'contribution_not_found' using errcode = 'P0002';
  end if;
  if v_author = new.voter_id then
    raise exception 'cannot_vote_self' using errcode = '22023';
  end if;
  if v_kind not in ('photo', 'video') then
    raise exception 'votes_are_for_media' using errcode = '22023';
  end if;
  if v_status <> 'active' then
    raise exception 'contribution_not_found' using errcode = 'P0002';
  end if;
  return new;
end;
$$;

create trigger contribution_votes_guard
  before insert or update on public.contribution_votes
  for each row execute function private.contribution_votes_guard();

-- Private helpers stay off PUBLIC. Policies and triggers that run as the
-- signed-in user keep execute; everything else is only reached from
-- security-definer functions.
revoke all on function private.contribution_target_visible(text, uuid, uuid) from public, anon, authenticated;
revoke all on function private.anonymous_display_allowed(text, uuid) from public, anon, authenticated;
revoke all on function private.contribution_point(text, uuid) from public, anon, authenticated;
revoke all on function private.wilson_lower(integer, integer) from public, anon, authenticated;
revoke all on function private.contribution_collapsed(integer, integer) from public, anon, authenticated;
revoke all on function private.contribution_readable_id(uuid, uuid) from public, anon, authenticated;
revoke all on function private.contribution_extension(text) from public, anon, authenticated;
revoke all on function private.contribution_objects_hide_owner() from public, anon, authenticated;
revoke all on function private.contribution_votes_guard() from public, anon, authenticated;
grant execute on function private.contribution_readable_id(uuid, uuid) to authenticated;
grant execute on function private.contribution_objects_hide_owner() to authenticated;
grant execute on function private.contribution_votes_guard() to authenticated;

alter table public.contributions enable row level security;
alter table public.contribution_media enable row level security;
alter table public.contribution_votes enable row level security;

revoke all on table public.contributions from public, anon, authenticated;
revoke all on table public.contribution_media from public, anon, authenticated;
revoke all on table public.contribution_votes from public, anon, authenticated;

-- author_id is intentionally absent. select * fails; the column is not granted.
grant select (
  id, target_kind, target_id, display_anonymous, kind, body, status, created_at, updated_at
) on public.contributions to authenticated;

create policy contributions_select_visible on public.contributions
  for select to authenticated
  using ((select private.contribution_readable_id(id, (select auth.uid()))));

grant select, insert, update, delete on public.contribution_votes to authenticated;

create policy contribution_votes_select_own on public.contribution_votes
  for select to authenticated
  using (voter_id = (select auth.uid()));

create policy contribution_votes_insert_own on public.contribution_votes
  for insert to authenticated
  with check (voter_id = (select auth.uid()));

create policy contribution_votes_update_own on public.contribution_votes
  for update to authenticated
  using (voter_id = (select auth.uid()))
  with check (voter_id = (select auth.uid()));

create policy contribution_votes_delete_own on public.contribution_votes
  for delete to authenticated
  using (voter_id = (select auth.uid()));

-- ---------------------------------------------------------------------------
-- Reads
-- ---------------------------------------------------------------------------
create function public.contribution_place(p_target_kind text, p_target_id uuid)
returns table (anonymous_allowed boolean, visible boolean)
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  if not private.contribution_target_visible(p_target_kind, p_target_id, auth.uid())
     and not exists (
       select 1 from public.drops d where p_target_kind in ('drop', 'marker') and d.id = p_target_id
     )
     and not exists (
       select 1 from public.open_data_items i where p_target_kind = 'open_data' and i.id = p_target_id
     )
     and not exists (
       select 1 from public.things t where p_target_kind = 'thing' and t.id = p_target_id
     )
     and not exists (
       select 1 from public.civic_items i where p_target_kind = 'civic' and i.id = p_target_id
     ) then
    raise exception 'place_not_found' using errcode = 'P0002';
  end if;
  anonymous_allowed := private.anonymous_display_allowed(p_target_kind, p_target_id);
  visible := private.contribution_target_visible(p_target_kind, p_target_id, auth.uid());
  return next;
end;
$$;

comment on function public.contribution_place(text, uuid) is
  'Whether this place can show an anonymous contribution. Does not return an author.';

create function public.contributions_for(
  p_target_kind text,
  p_target_id uuid,
  p_sort text default 'newest'
)
returns table (
  id uuid,
  target_kind text,
  target_id uuid,
  kind text,
  body text,
  created_at timestamptz,
  live boolean,
  up_count integer,
  down_count integer,
  score double precision,
  my_vote smallint,
  author_handle text,
  anonymous boolean,
  is_mine boolean,
  collapsed boolean,
  media jsonb
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_sort text := coalesce(p_sort, 'newest');
begin
  if v_sort not in ('newest', 'top') then
    raise exception 'invalid_sort' using errcode = '22023';
  end if;
  if p_target_kind is null or p_target_id is null then
    raise exception 'place_not_found' using errcode = 'P0002';
  end if;

  return query
  with tallies as (
    select v.contribution_id,
           count(*) filter (where v.value = 1)::integer as up_n,
           count(*) filter (where v.value = -1)::integer as down_n
    from public.contribution_votes v
    group by v.contribution_id
  )
  select c.id,
         c.target_kind,
         c.target_id,
         c.kind,
         c.body,
         c.created_at,
         c.created_at > now() - interval '24 hours',
         coalesce(t.up_n, 0),
         coalesce(t.down_n, 0),
         private.wilson_lower(coalesce(t.up_n, 0), coalesce(t.down_n, 0)),
         mine.value,
         case when c.display_anonymous then null else p.handle end,
         c.display_anonymous,
         v_uid is not null and c.author_id = v_uid,
         private.contribution_collapsed(coalesce(t.up_n, 0), coalesce(t.down_n, 0)),
         case when m.id is null then '[]'::jsonb else jsonb_build_array(jsonb_build_object(
           'storage_bucket', m.storage_bucket,
           'storage_path', m.storage_path,
           'thumb_path', m.thumb_path,
           'mime_type', m.mime_type,
           'byte_size', m.byte_size,
           'width', m.width,
           'height', m.height,
           'duration_s', m.duration_s,
           'alt_text', m.alt_text
         )) end
  from public.contributions c
  left join tallies t on t.contribution_id = c.id
  left join public.profiles p on p.id = c.author_id and not c.display_anonymous
  left join public.contribution_votes mine
    on mine.contribution_id = c.id and v_uid is not null and mine.voter_id = v_uid
  left join public.contribution_media m on m.contribution_id = c.id
  where c.target_kind = p_target_kind
    and c.target_id = p_target_id
    and private.contribution_readable_id(c.id, v_uid)
  order by
    case when v_sort = 'top' then private.wilson_lower(coalesce(t.up_n, 0), coalesce(t.down_n, 0)) end desc nulls last,
    c.created_at desc;
end;
$$;

comment on function public.contributions_for(text, uuid, text) is
  'Newest or top contributions for one place. Anonymous rows have a null handle and no author id.';

create function public.contribution_author(p_id uuid)
returns uuid
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_author uuid;
begin
  if not private.is_moderator() then
    raise exception 'not_moderator' using errcode = '42501';
  end if;
  select c.author_id into v_author from public.contributions c where c.id = p_id;
  if v_author is null then
    raise exception 'contribution_not_found' using errcode = 'P0002';
  end if;
  return v_author;
end;
$$;

comment on function public.contribution_author(uuid) is
  'Moderator-only. The public list never returns this id.';

create function public.live_places_in_view(
  p_min_lng double precision,
  p_min_lat double precision,
  p_max_lng double precision,
  p_max_lat double precision
)
returns table (
  target_kind text,
  target_id uuid,
  lat double precision,
  lng double precision
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_envelope extensions.geography;
begin
  perform private.make_point(p_min_lat, p_min_lng);
  perform private.make_point(p_max_lat, p_max_lng);
  if p_min_lng >= p_max_lng or p_min_lat >= p_max_lat then
    raise exception 'invalid_bbox' using errcode = '22023';
  end if;
  v_envelope := extensions.st_makeenvelope(p_min_lng, p_min_lat, p_max_lng, p_max_lat, 4326)::extensions.geography;

  return query
  with live as (
    select c.target_kind as k,
           c.target_id as i,
           private.contribution_point(c.target_kind, c.target_id) as geog
    from public.contributions c
    where c.status = 'active'
      and c.kind in ('photo', 'video')
      and c.created_at > now() - interval '24 hours'
      and private.contribution_readable_id(c.id, v_uid)
  )
  select distinct l.k, l.i,
         extensions.st_y(l.geog::extensions.geometry),
         extensions.st_x(l.geog::extensions.geometry)
  from live l
  where l.geog is not null
    and extensions.st_intersects(l.geog, v_envelope)
  limit 500;
end;
$$;

comment on function public.live_places_in_view(double precision, double precision, double precision, double precision) is
  'Places with a photo or video from the last 24 hours. No author is returned.';

-- ---------------------------------------------------------------------------
-- Writes
-- ---------------------------------------------------------------------------
create function public.create_contribution(
  p_target_kind text,
  p_target_id uuid,
  p_kind text,
  p_body text default null,
  p_display_anonymous boolean default false,
  p_media jsonb default '[]'::jsonb
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_profile public.profiles;
  v_limit integer;
  v_recent integer;
  v_body text := nullif(btrim(p_body), '');
  v_mode public.moderation_mode := 'post'::public.moderation_mode;
  v_status text := 'active';
  v_id uuid := gen_random_uuid();
  v_item jsonb;
  v_mime text;
  v_ext text;
  v_media_id uuid;
  v_path text;
  v_thumb text;
  v_bytes integer;
  v_width integer;
  v_height integer;
  v_duration numeric;
  v_alt text;
  v_thumb_bytes integer;
  v_slots jsonb := '[]'::jsonb;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  select * into v_profile from public.profiles p where p.id = v_uid;
  if not found or v_profile.deleted_at is not null then
    raise exception 'account_unavailable' using errcode = '42501';
  end if;
  if exists (
    select 1 from public.user_moderation m
    where m.user_id = v_uid and m.suspended_until > now()
  ) then
    raise exception 'account_suspended' using errcode = '42501';
  end if;
  if not private.contribution_target_visible(p_target_kind, p_target_id, v_uid) then
    raise exception 'place_not_found' using errcode = 'P0002';
  end if;
  if coalesce(p_display_anonymous, false) and not private.anonymous_display_allowed(p_target_kind, p_target_id) then
    raise exception 'anonymous_not_allowed' using errcode = '22023';
  end if;
  if p_kind not in ('text', 'photo', 'video') then
    raise exception 'invalid_contribution' using errcode = '22023';
  end if;
  if p_kind = 'text' and (v_body is null or char_length(v_body) > 500) then
    raise exception 'invalid_contribution' using errcode = '22023', detail = 'text is 1..500 characters';
  end if;
  if p_kind <> 'text' and (v_body is not null and char_length(v_body) > 500) then
    raise exception 'invalid_contribution' using errcode = '22023';
  end if;
  if p_media is null or jsonb_typeof(p_media) <> 'array' or jsonb_array_length(p_media) > 1 then
    raise exception 'invalid_contribution' using errcode = '22023';
  end if;
  if p_kind = 'text' and jsonb_array_length(p_media) <> 0 then
    raise exception 'invalid_contribution' using errcode = '22023';
  end if;
  if p_kind <> 'text' and jsonb_array_length(p_media) <> 1 then
    raise exception 'invalid_contribution' using errcode = '22023', detail = 'a photo or video needs one file';
  end if;

  select tl.contributions_per_day into v_limit
  from public.tier_limits tl
  where tl.tier = v_profile.tier;
  select count(*) into v_recent
  from public.contributions c
  where c.author_id = v_uid and c.created_at > now() - interval '1 day';
  if v_recent >= coalesce(v_limit, 0) then
    raise exception 'rate_limited' using errcode = 'PT429',
      detail = format('tier %s allows %s contributions per day', v_profile.tier, coalesce(v_limit, 0));
  end if;

  if p_target_kind in ('drop', 'marker', 'open_data') then
    select c.moderation_mode into v_mode
    from public.drops d
    join public.categories c on c.id = d.category_id
    left join public.open_data_items i on i.drop_id = d.id
    where (p_target_kind in ('drop', 'marker') and d.id = p_target_id)
       or (p_target_kind = 'open_data' and i.id = p_target_id);
  elsif p_target_kind = 'thing' then
    select coalesce(c.moderation_mode, 'post'::public.moderation_mode) into v_mode
    from public.things t
    left join public.categories c on c.slug = t.category_slug
    where t.id = p_target_id;
  end if;
  if v_mode = 'pre' or (v_mode = 'pre_for_new' and v_profile.tier = 'new') then
    v_status := 'pending_review';
  end if;

  insert into public.contributions (
    id, target_kind, target_id, author_id, display_anonymous, kind, body, status
  ) values (
    v_id, p_target_kind, p_target_id, v_uid, coalesce(p_display_anonymous, false), p_kind, v_body, v_status
  );

  if p_kind <> 'text' then
    v_item := p_media -> 0;
    v_mime := v_item ->> 'mime_type';
    v_ext := private.contribution_extension(v_mime);
    v_bytes := (v_item ->> 'byte_size')::integer;
    v_width := nullif(v_item ->> 'width', '')::integer;
    v_height := nullif(v_item ->> 'height', '')::integer;
    v_duration := nullif(v_item ->> 'duration_s', '')::numeric;
    v_alt := nullif(btrim(v_item ->> 'alt_text'), '');
    v_thumb_bytes := nullif(v_item ->> 'thumb_byte_size', '')::integer;
    if v_ext is null or v_bytes is null then
      raise exception 'invalid_contribution' using errcode = '22023';
    end if;
    if p_kind = 'photo' and (v_mime not like 'image/%' or v_bytes > 512000) then
      raise exception 'invalid_contribution' using errcode = '22023', detail = 'photos are at most 500 KB';
    end if;
    if p_kind = 'video' and (v_mime not like 'video/%' or v_bytes > 8388608 or v_duration is null or v_duration > 30) then
      raise exception 'invalid_contribution' using errcode = '22023', detail = 'videos are at most 30 seconds and 8 MB';
    end if;
    if p_kind = 'video' and (v_width is null or v_height is null or greatest(v_width, v_height) > 1280) then
      raise exception 'invalid_contribution' using errcode = '22023', detail = 'videos are at most 720p';
    end if;
    if p_kind = 'photo' and v_width is not null and v_height is not null and greatest(v_width, v_height) > 1600 then
      raise exception 'invalid_contribution' using errcode = '22023', detail = 'photos are at most 1600 px';
    end if;
    if v_alt is not null and char_length(v_alt) > 300 then
      raise exception 'invalid_contribution' using errcode = '22023';
    end if;
    v_media_id := gen_random_uuid();
    v_path := 'c/' || v_id::text || '/' || v_media_id::text || '.' || v_ext;
    if p_kind = 'video' then
      if v_thumb_bytes is null or v_thumb_bytes < 1 or v_thumb_bytes > 512000 then
        raise exception 'invalid_contribution' using errcode = '22023', detail = 'a video needs a thumbnail';
      end if;
      v_thumb := 'c/' || v_id::text || '/' || v_media_id::text || '.thumb.jpg';
    else
      v_thumb := null;
    end if;
    if position(v_uid::text in v_path) > 0 or (v_thumb is not null and position(v_uid::text in v_thumb) > 0) then
      raise exception 'invalid_contribution' using errcode = '22023';
    end if;
    insert into public.contribution_media (
      id, contribution_id, storage_path, thumb_path, mime_type, byte_size, width, height, duration_s, alt_text
    ) values (
      v_media_id, v_id, v_path, v_thumb, v_mime, v_bytes, v_width, v_height, v_duration, v_alt
    );
    v_slots := jsonb_build_array(jsonb_build_object(
      'id', v_media_id,
      'storage_bucket', 'contribution-media',
      'storage_path', v_path,
      'thumb_path', v_thumb,
      'mime_type', v_mime
    ));
  end if;

  return jsonb_build_object(
    'id', v_id,
    'status', v_status,
    'anonymous', coalesce(p_display_anonymous, false),
    'media', v_slots
  );
end;
$$;

comment on function public.create_contribution(text, uuid, text, text, boolean, jsonb) is
  'Adds a contribution and reserves a non-identifying storage path. The return value has no author id.';

create function public.cast_contribution_vote(p_id uuid, p_value smallint)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_recent integer;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  if p_value is null or p_value not in (-1, 0, 1) then
    raise exception 'invalid_vote' using errcode = '22023';
  end if;
  if not private.contribution_readable_id(p_id, v_uid) then
    raise exception 'contribution_not_found' using errcode = 'P0002';
  end if;
  if p_value = 0 then
    delete from public.contribution_votes v
    where v.contribution_id = p_id and v.voter_id = v_uid;
    return;
  end if;
  if not exists (
    select 1 from public.contribution_votes v
    where v.contribution_id = p_id and v.voter_id = v_uid
  ) then
    select count(*) into v_recent
    from public.contribution_votes v
    where v.voter_id = v_uid and v.created_at > now() - interval '1 hour';
    if v_recent >= 120 then
      raise exception 'rate_limited' using errcode = 'PT429',
        detail = '120 new votes per hour';
    end if;
  end if;
  insert into public.contribution_votes (contribution_id, voter_id, value)
  values (p_id, v_uid, p_value)
  on conflict (contribution_id, voter_id) do update
    set value = excluded.value, updated_at = now();
end;
$$;

comment on function public.cast_contribution_vote(uuid, smallint) is
  'One vote per person. 1 or -1 sets it, 0 removes it. The author cannot vote.';

create function public.delete_my_contribution(p_id uuid)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  update public.contributions
  set status = 'removed'
  where id = p_id and author_id = v_uid and status <> 'removed';
  if not found then
    raise exception 'contribution_not_found' using errcode = 'P0002';
  end if;
end;
$$;

create function public.moderate_contribution(p_id uuid, p_action text, p_reason text default null)
returns text
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_row public.contributions;
  v_next text;
begin
  if not private.is_moderator() then
    raise exception 'not_moderator' using errcode = '42501';
  end if;
  select * into v_row from public.contributions c where c.id = p_id;
  if not found then
    raise exception 'contribution_not_found' using errcode = 'P0002';
  end if;
  if v_row.author_id = v_uid then
    raise exception 'cannot_moderate_own_drop' using errcode = '42501';
  end if;
  if p_action = 'approve' then
    if v_row.status <> 'pending_review' and v_row.status <> 'hidden' then
      raise exception 'invalid_transition' using errcode = '22023';
    end if;
    v_next := 'active';
  elsif p_action = 'remove' then
    v_next := 'removed';
  elsif p_action = 'restore' then
    if v_row.status <> 'removed' and v_row.status <> 'hidden' then
      raise exception 'invalid_transition' using errcode = '22023';
    end if;
    v_next := 'active';
  else
    raise exception 'invalid_transition' using errcode = '22023';
  end if;
  update public.contributions set status = v_next where id = p_id;
  insert into public.moderation_actions (actor_id, target_type, target_id, action, reason)
  values (
    v_uid,
    'contribution',
    p_id,
    case p_action when 'approve' then 'approve'::public.moderation_action_type
                  when 'restore' then 'restore'::public.moderation_action_type
                  else 'remove'::public.moderation_action_type end,
    nullif(btrim(p_reason), '')
  );
  return v_next;
end;
$$;

comment on function public.moderate_contribution(uuid, text, text) is
  'Moderator approve, remove, or restore. Report rows are closed by report() once the contribution target exists.';

-- moderation_actions.target_type is text, so 'contribution' is valid here.
-- reports.target_type is still the enum. The next migration adds that value
-- and teaches report() about contributions. This function does not write a report.

revoke all on function public.contribution_place(text, uuid) from public;
revoke all on function public.contributions_for(text, uuid, text) from public;
revoke all on function public.contribution_author(uuid) from public;
revoke all on function public.live_places_in_view(double precision, double precision, double precision, double precision) from public;
revoke all on function public.create_contribution(text, uuid, text, text, boolean, jsonb) from public;
revoke all on function public.cast_contribution_vote(uuid, smallint) from public;
revoke all on function public.delete_my_contribution(uuid) from public;
revoke all on function public.moderate_contribution(uuid, text, text) from public;

grant execute on function public.contribution_place(text, uuid) to anon, authenticated;
grant execute on function public.contributions_for(text, uuid, text) to anon, authenticated;
grant execute on function public.live_places_in_view(double precision, double precision, double precision, double precision) to anon, authenticated;
grant execute on function public.contribution_author(uuid) to authenticated;
grant execute on function public.create_contribution(text, uuid, text, text, boolean, jsonb) to authenticated;
grant execute on function public.cast_contribution_vote(uuid, smallint) to authenticated;
grant execute on function public.delete_my_contribution(uuid) to authenticated;
grant execute on function public.moderate_contribution(uuid, text, text) to authenticated;

-- New enum value. It cannot be used until the next migration's transaction.
alter type public.report_target add value if not exists 'contribution';
