-- M6: close two gaps in the M1 read/confirm RPCs.
--
-- 1. private.to_drop_card copied tags even when the caller was outside the
--    reveal radius, and drop_teaser left them in place. A teaser is the title,
--    category, and display position (PLAN §5). Tags are part of the drop, same
--    as the body, so they stay null until the drop is revealed.
--
-- 2. confirm_drop counted an existing confirmation toward the hourly cap, so
--    changing your mind on a drop you already answered was rejected once you
--    were at the limit. An update is still one confirmation. Only other drops
--    answered in the last hour count.

create or replace function private.to_drop_card(
  p_drop public.drops,
  p_viewer uuid,
  p_point extensions.geography
)
returns public.drop_card
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_card public.drop_card;
  v_category public.categories;
  v_distance double precision;
begin
  select * into v_category from public.categories c where c.id = p_drop.category_id;

  v_card.id := p_drop.id;
  v_card.short_id := p_drop.short_id;
  v_card.category_slug := v_category.slug;
  v_card.group_slug := (select g.slug from public.category_groups g where g.id = v_category.group_id);
  v_card.title := p_drop.title;
  v_card.lat := extensions.st_y(p_drop.display_geog::extensions.geometry);
  v_card.lng := extensions.st_x(p_drop.display_geog::extensions.geometry);
  v_card.location_precision_m := v_category.location_precision_m;
  v_card.reveal_radius_m := p_drop.reveal_radius_m;
  v_card.status := p_drop.status;
  v_card.is_confirmed := p_drop.status = 'confirmed';
  v_card.confirm_count := p_drop.confirm_count;
  v_card.deny_count := p_drop.deny_count;
  v_card.expires_at := p_drop.expires_at;
  v_card.last_confirmed_at := p_drop.last_confirmed_at;
  v_card.created_at := p_drop.created_at;
  v_card.is_mine := p_viewer is not null and p_drop.author_id is not distinct from p_viewer;

  if p_drop.author_id is not null
     and not (p_drop.display_anonymous and v_category.allow_anonymous_display) then
    v_card.author_handle := (select p.handle from public.profiles p where p.id = p_drop.author_id);
  end if;

  if p_point is not null then
    v_distance := extensions.st_distance(p_drop.display_geog, p_point);
    v_card.distance_m := round(v_distance::numeric, 1)::double precision;
  end if;

  v_card.revealed := v_card.is_mine or (v_distance is not null and v_distance <= p_drop.reveal_radius_m);

  if v_card.revealed then
    v_card.body := p_drop.body;
    v_card.attributes := p_drop.attributes;
    v_card.tags := p_drop.tags;
    v_card.media := coalesce((
      select jsonb_agg(jsonb_build_object(
          'id', m.id,
          'storage_bucket', m.storage_bucket,
          'storage_path', m.storage_path,
          'media_kind', m.media_kind,
          'mime_type', m.mime_type,
          'width', m.width,
          'height', m.height,
          'duration_s', m.duration_s,
          'taken_here', m.taken_here
        ) order by m.sort, m.created_at)
      from public.drop_media m
      where m.drop_id = p_drop.id
    ), '[]'::jsonb);
  end if;

  return v_card;
end;
$$;

create or replace function public.drop_teaser(p_short_id text)
returns public.drop_card
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_drop public.drops;
  v_card public.drop_card;
begin
  select * into v_drop from public.drops d where d.short_id = p_short_id;
  if not found or not private.can_view_drop(v_drop, v_uid) then
    raise exception 'drop_not_found' using errcode = 'P0002';
  end if;

  v_card := private.to_drop_card(v_drop, v_uid, null);
  -- Share pages never reveal, including for the author. The app calls
  -- drop_detail with a location when the person is actually there.
  v_card.revealed := false;
  v_card.body := null;
  v_card.attributes := null;
  v_card.media := null;
  v_card.tags := null;
  return v_card;
end;
$$;

create or replace function public.confirm_drop(
  p_drop_id uuid,
  p_verdict public.confirmation_verdict,
  p_lat double precision,
  p_lng double precision,
  p_note text default null
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
  v_drop public.drops;
  v_point extensions.geography;
  v_distance double precision;
  v_limit integer;
  v_recent integer;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  select * into v_profile from public.profiles p where p.id = v_uid;
  if not found or v_profile.deleted_at is not null then
    raise exception 'account_unavailable' using errcode = '42501';
  end if;
  if exists (select 1 from public.user_moderation m where m.user_id = v_uid and m.suspended_until > now()) then
    raise exception 'account_suspended' using errcode = '42501';
  end if;
  if p_verdict is null then
    raise exception 'invalid_verdict' using errcode = '22023';
  end if;
  if char_length(p_note) > 280 then
    raise exception 'invalid_note' using errcode = '22023', detail = 'note must be at most 280 characters';
  end if;

  select * into v_drop from public.drops d where d.id = p_drop_id for update;
  if not found or not private.can_view_drop(v_drop, v_uid) or v_drop.status not in ('active', 'confirmed') then
    raise exception 'drop_not_found' using errcode = 'P0002';
  end if;
  if v_drop.author_id = v_uid then
    raise exception 'cannot_confirm_own_drop' using errcode = '42501';
  end if;

  v_point := private.make_point(p_lat, p_lng);
  v_distance := extensions.st_distance(v_drop.display_geog, v_point);
  if v_distance > greatest(50, v_drop.reveal_radius_m) then
    raise exception 'too_far' using errcode = '22023',
      detail = format('%s m away; must be within %s m', round(v_distance::numeric), greatest(50, v_drop.reveal_radius_m));
  end if;

  select tl.confirmations_per_hour into v_limit from public.tier_limits tl where tl.tier = v_profile.tier;
  -- Other drops answered this hour. Updating this drop does not take another slot.
  select count(*) into v_recent
  from public.confirmations c
  where c.user_id = v_uid
    and c.drop_id <> v_drop.id
    and c.updated_at > now() - interval '1 hour';
  if v_recent >= coalesce(v_limit, 0) then
    raise exception 'rate_limited' using errcode = 'PT429',
      detail = format('tier %s allows %s confirmations per hour', v_profile.tier, coalesce(v_limit, 0));
  end if;

  insert into public.confirmations (drop_id, user_id, verdict, note, distance_m)
  values (v_drop.id, v_uid, p_verdict, nullif(btrim(p_note), ''), round(v_distance)::integer)
  on conflict (drop_id, user_id) do update
    set verdict = excluded.verdict, note = excluded.note, distance_m = excluded.distance_m;

  if p_verdict = 'still_true' then
    update public.drops d
    set last_confirmed_at = now(),
        expires_at = case
          when c.confirm_extends_ttl is not null and d.expires_at is not null then
            least(greatest(d.expires_at, now() + c.confirm_extends_ttl),
                  coalesce(now() + c.max_ttl, 'infinity'::timestamptz))
          else d.expires_at
        end
    from public.categories c
    where d.id = v_drop.id and c.id = d.category_id;
  end if;

  select * into v_drop from public.drops d where d.id = p_drop_id;
  return jsonb_build_object(
    'drop_id', v_drop.id,
    'verdict', p_verdict,
    'status', v_drop.status,
    'is_confirmed', v_drop.status = 'confirmed',
    'confirm_count', v_drop.confirm_count,
    'deny_count', v_drop.deny_count,
    'expires_at', v_drop.expires_at,
    'distance_m', round(v_distance)::integer
  );
end;
$$;
