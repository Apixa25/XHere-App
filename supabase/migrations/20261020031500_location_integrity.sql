-- M12: location integrity for drops, confirms, reveals, and walk check-ins.
--
-- Free signals only. The phone may send a coarse integrity object (mock bit,
-- developer-options bit, accuracy, fix time, device time, and whether the point
-- came from the location provider). Play Integrity is not called. The standard
-- API has no per-request charge, but a verdict is only meaningful after Google
-- decrypts the token, and that needs a Play Console app linked to a Cloud
-- project. The Play developer account is a cost item in PLAN §8 and is not set
-- up here. When that account exists, a later migration can accept a token and
-- fold its verdict into private.location_decision. Do not add a client boolean
-- for it: the phone could lie.
--
-- Contract for badges, quests, and campaigns (M11, Founding Locals). Read these
-- columns. Do not infer credit from the mere existence of a row.
--   public.drops.counts_for_credit          false => do not award for this drop
--   public.drops.location_trust             'standard' or 'reduced' (same fact)
--   public.confirmations.counts_for_credit  false => do not award for this answer
--   public.place_visits                     inserted only when the check-in counted
--   public.walk_stop_reaches                inserted only when the check-in counted
-- public.counts_for_location_credit(action, user, target) is the same rule for
-- a caller asking about their own rows. A security-definer badge job can read
-- the columns directly.
--
-- Outcome is a soft flag. The account is not suspended. The action still exists.
-- A suspicious reveal of someone else's drop does not return the body.
--
-- Privacy (PLAN §5). One checkpoint per person, overwritten, kept for 2 hours,
-- and only so the next action can be checked for impossible speed. It lives in
-- private and is not exported. Flags keep the reason codes and the action, not
-- coordinates, for 7 days, so a moderator can see them. Both are deleted by
-- xhere-purge-location-integrity and when the profile is hard-deleted.
--
-- Thresholds are the same numbers as world.xhere.shared.integrity.LocationIntegrity.
--   speed: faster than 150 m/s, at least 2 km, at least 20 s since the checkpoint
--   accuracy: over 1000 m to post, over 250 m to confirm, reveal, or check in
--   fix older than 10 min, or more than 5 min in the future
--   device clock more than 5 min from the server
--   developer options alone do not remove credit; they are noted on a flag that
--   already has another reason
--   a map pin (device_fix false) does not count and does not move the checkpoint

alter table public.drops
  add column location_trust text not null default 'standard'
    check (location_trust in ('standard', 'reduced')),
  add column counts_for_credit boolean not null default true;

comment on column public.drops.counts_for_credit is
  'False when the post failed the location check. Badges, quests, and campaigns must skip it.';
comment on column public.drops.location_trust is
  'standard or reduced. Reduced matches counts_for_credit = false. Not a ban.';

alter table public.confirmations
  add column counts_for_credit boolean not null default true;

comment on column public.confirmations.counts_for_credit is
  'False when this answer failed the location check. Counters, quests, and badges skip it.';

alter type public.drop_card add attribute location_trust text;
alter type public.drop_card add attribute location_withheld boolean;

-- One previous point. Not a trail: the row is replaced, then deleted after 2 hours.
create table private.location_checkpoints (
  user_id uuid primary key references public.profiles (id) on delete cascade,
  geog extensions.geography(Point, 4326) not null,
  observed_at timestamptz not null,
  expires_at timestamptz not null
);

comment on table private.location_checkpoints is
  'Last device fix for one person, kept 2 hours for an impossible-travel check. Not a history.';

alter table private.location_checkpoints enable row level security;
revoke all on table private.location_checkpoints from public, anon, authenticated;

-- Reason codes only. No coordinates.
create table public.location_integrity_flags (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles (id) on delete cascade,
  action text not null check (action in ('create_drop', 'confirm_drop', 'reveal', 'walk_check_in')),
  target_id uuid,
  reasons text[] not null check (
    cardinality(reasons) > 0
    and reasons <@ array[
      'mock_location', 'impossible_travel', 'accuracy_too_large', 'stale_fix',
      'clock_skew', 'unverified_fix', 'developer_options'
    ]::text[]
  ),
  created_at timestamptz not null default now(),
  expires_at timestamptz not null
);

comment on table public.location_integrity_flags is
  'Soft location flags for moderators. Reason codes only, kept 7 days. No coordinates, no automatic ban.';

create index location_integrity_flags_queue_idx
  on public.location_integrity_flags (created_at desc);
create index location_integrity_flags_user_idx
  on public.location_integrity_flags (user_id, created_at desc);
create index location_integrity_flags_expires_idx
  on public.location_integrity_flags (expires_at);

alter table public.location_integrity_flags enable row level security;
revoke all on table public.location_integrity_flags from anon, authenticated;

grant select on public.location_integrity_flags to authenticated;

create policy location_integrity_flags_select on public.location_integrity_flags
  for select to authenticated
  using (user_id = (select auth.uid()) or (select private.is_moderator()));

-- ---------------------------------------------------------------------------
-- Decision. Updates the single checkpoint unless the point was not a device fix.
-- Does not insert the flag; callers do that when the failed action matters.
-- ---------------------------------------------------------------------------
create function private.location_decision(
  p_uid uuid,
  p_action text,
  p_point extensions.geography,
  p_integrity jsonb
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_raw jsonb := case
    when p_integrity is null or jsonb_typeof(p_integrity) <> 'object' then '{}'::jsonb
    else p_integrity
  end;
  v_device boolean := true;
  v_mock boolean := false;
  v_dev boolean := false;
  v_accuracy double precision;
  v_limit double precision;
  v_fix_at timestamptz;
  v_device_at timestamptz;
  v_text text;
  v_ms double precision;
  v_prev extensions.geography;
  v_prev_at timestamptz;
  v_elapsed double precision;
  v_distance double precision;
  v_reasons text[] := '{}'::text[];
begin
  perform pg_advisory_xact_lock(hashtextextended('xhere.location:' || p_uid::text, 0));

  if v_raw ? 'device_fix' and jsonb_typeof(v_raw -> 'device_fix') = 'boolean' then
    v_device := (v_raw ->> 'device_fix') = 'true';
  end if;
  v_mock := jsonb_typeof(v_raw -> 'mock') = 'boolean' and (v_raw ->> 'mock') = 'true';
  v_dev := jsonb_typeof(v_raw -> 'developer_options') = 'boolean' and (v_raw ->> 'developer_options') = 'true';

  if not v_device then
    v_reasons := array_append(v_reasons, 'unverified_fix');
  end if;
  if v_mock then
    v_reasons := array_append(v_reasons, 'mock_location');
  end if;

  if v_device then
    v_text := v_raw ->> 'accuracy_m';
    if v_text ~ '^[0-9]{1,6}(\.[0-9]{1,3})?$' then
      v_accuracy := least(v_text::double precision, 100000);
      v_limit := case when p_action = 'create_drop' then 1000 else 250 end;
      if v_accuracy > v_limit then
        v_reasons := array_append(v_reasons, 'accuracy_too_large');
      end if;
    end if;

    if jsonb_typeof(v_raw -> 'fix_at') = 'string' then
      v_text := v_raw ->> 'fix_at';
      if v_text ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$' then
        v_fix_at := v_text::timestamptz;
      end if;
    elsif jsonb_typeof(v_raw -> 'fix_at') = 'number' then
      v_ms := (v_raw ->> 'fix_at')::double precision;
      if v_ms between 0 and 4102444800000 then
        v_fix_at := pg_catalog.to_timestamp(v_ms / 1000.0);
      end if;
    end if;
    if v_fix_at is not null then
      if v_fix_at > now() + interval '5 minutes' then
        v_reasons := array_append(v_reasons, 'clock_skew');
      elsif v_fix_at < now() - interval '10 minutes' then
        v_reasons := array_append(v_reasons, 'stale_fix');
      end if;
    end if;

    if jsonb_typeof(v_raw -> 'device_at') = 'string' then
      v_text := v_raw ->> 'device_at';
      if v_text ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$' then
        v_device_at := v_text::timestamptz;
      end if;
    elsif jsonb_typeof(v_raw -> 'device_at') = 'number' then
      v_ms := (v_raw ->> 'device_at')::double precision;
      if v_ms between 0 and 4102444800000 then
        v_device_at := pg_catalog.to_timestamp(v_ms / 1000.0);
      end if;
    end if;
    if v_device_at is not null
       and abs(extract(epoch from (v_device_at - now()))) > 300
       and not ('clock_skew' = any (v_reasons)) then
      v_reasons := array_append(v_reasons, 'clock_skew');
    end if;

    select c.geog, c.observed_at into v_prev, v_prev_at
    from private.location_checkpoints c
    where c.user_id = p_uid and c.expires_at > now();
    if v_prev is not null then
      v_elapsed := extract(epoch from (now() - v_prev_at));
      v_distance := extensions.st_distance(v_prev, p_point);
      if v_elapsed >= 20 and v_distance >= 2000 and v_distance / v_elapsed > 150 then
        v_reasons := array_append(v_reasons, 'impossible_travel');
      end if;
    end if;

    insert into private.location_checkpoints (user_id, geog, observed_at, expires_at)
    values (p_uid, p_point, now(), now() + interval '2 hours')
    on conflict (user_id) do update
      set geog = excluded.geog,
          observed_at = excluded.observed_at,
          expires_at = excluded.expires_at;
  end if;

  if v_dev and cardinality(v_reasons) > 0 then
    v_reasons := array_append(v_reasons, 'developer_options');
  end if;

  return jsonb_build_object(
    'credit', cardinality(v_reasons) = 0,
    'trust', case when cardinality(v_reasons) = 0 then 'standard' else 'reduced' end,
    'reasons', to_jsonb(v_reasons)
  );
end;
$$;

create function private.record_location_flag(
  p_uid uuid,
  p_action text,
  p_target uuid,
  p_reasons jsonb
)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_reasons text[] := coalesce(
    (select array_agg(x.value) from jsonb_array_elements_text(coalesce(p_reasons, '[]'::jsonb)) as x(value)),
    '{}'::text[]
  );
begin
  if cardinality(v_reasons) = 0 then
    return;
  end if;
  if exists (
    select 1 from public.location_integrity_flags f
    where f.user_id = p_uid
      and f.action = p_action
      and f.target_id is not distinct from p_target
      and f.reasons = v_reasons
      and f.created_at > now() - interval '1 hour'
  ) then
    return;
  end if;
  insert into public.location_integrity_flags (user_id, action, target_id, reasons, expires_at)
  values (p_uid, p_action, p_target, v_reasons, now() + interval '7 days');
end;
$$;

-- Capture coordinates are an argument, not a column. A client-sent taken_here is ignored.
create function private.photo_taken_here(
  p_point extensions.geography,
  p_item jsonb,
  p_credit boolean,
  p_integrity jsonb
)
returns boolean
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_lat double precision;
  v_lng double precision;
  v_accuracy double precision;
  v_text text;
  v_raw jsonb := case
    when p_integrity is null or jsonb_typeof(p_integrity) <> 'object' then '{}'::jsonb
    else p_integrity
  end;
begin
  if not coalesce(p_credit, false) or p_item is null or jsonb_typeof(p_item) <> 'object' then
    return false;
  end if;
  v_text := p_item ->> 'capture_lat';
  if v_text is null or v_text !~ '^-?[0-9]{1,3}(\.[0-9]+)?$' then
    return false;
  end if;
  v_lat := v_text::double precision;
  v_text := p_item ->> 'capture_lng';
  if v_text is null or v_text !~ '^-?[0-9]{1,3}(\.[0-9]+)?$' then
    return false;
  end if;
  v_lng := v_text::double precision;
  if v_lat not between -90 and 90 or v_lng not between -180 and 180 then
    return false;
  end if;
  v_text := v_raw ->> 'accuracy_m';
  if v_text ~ '^[0-9]{1,6}(\.[0-9]{1,3})?$' then
    v_accuracy := least(v_text::double precision, 100000);
  end if;
  return extensions.st_distance(p_point, private.make_point(v_lat, v_lng))
    <= least(greatest(150, coalesce(v_accuracy, 150)), 500);
end;
$$;

create function private.purge_location_integrity()
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_count integer := 0;
  v_n integer;
begin
  delete from private.location_checkpoints where expires_at <= now();
  get diagnostics v_n = row_count;
  v_count := v_n;
  delete from public.location_integrity_flags where expires_at <= now();
  get diagnostics v_n = row_count;
  return v_count + v_n;
end;
$$;

-- Counters follow bars, and a flagged answer does not move them.
create or replace function private.refresh_drop_confirmations(p_drop_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_confirms integer;
  v_denies integer;
begin
  select count(*) filter (where c.verdict = 'still_true' and c.counts_for_credit),
         count(*) filter (where c.verdict = 'not_true' and c.counts_for_credit)
    into v_confirms, v_denies
  from public.confirmations c
  where c.drop_id = p_drop_id;

  update public.drops d
  set confirm_count = v_confirms,
      deny_count = v_denies,
      status = case
        when d.status not in ('active', 'confirmed', 'disputed') then d.status
        when v_denies >= cat.denials_to_hide and v_denies > v_confirms then 'disputed'::public.drop_status
        when v_confirms >= cat.confirmations_to_confirm then 'confirmed'::public.drop_status
        else 'active'::public.drop_status
      end,
      expires_at = case
        when cat.permanent_when_confirmed
             and d.status in ('active', 'confirmed', 'disputed')
             and v_confirms >= cat.confirmations_to_confirm
             and not (v_denies >= cat.denials_to_hide and v_denies > v_confirms)
          then null
        else d.expires_at
      end
  from public.categories cat
  where d.id = p_drop_id and cat.id = d.category_id;
end;
$$;

create or replace function private.confirmations_note_helped()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_author uuid;
begin
  if new.counts_for_credit
     and new.verdict = 'still_true'
     and (
       tg_op = 'INSERT'
       or old.counts_for_credit is not true
       or old.verdict is distinct from 'still_true'
     ) then
    select d.author_id into v_author from public.drops d where d.id = new.drop_id;
    perform private.note_drop_helped(v_author, new.drop_id, 'confirm');
  end if;
  return null;
end;
$$;

drop trigger confirmations_note_helped on public.confirmations;
create trigger confirmations_note_helped
  after insert or update of verdict, counts_for_credit on public.confirmations
  for each row execute function private.confirmations_note_helped();

-- Quest steps count only actions that passed the location check.
create or replace function private.quest_step_progress(p_uid uuid, p_kind text)
returns integer
language sql
stable
security definer
set search_path = ''
as $$
  select case p_kind
    when 'visit_places' then (
      select count(*)::integer from public.place_visits v where v.user_id = p_uid
    )
    when 'confirm_drop' then (
      select count(*)::integer from public.confirmations c
      where c.user_id = p_uid and c.verdict = 'still_true' and c.counts_for_credit
    )
    when 'create_drop' then (
      select count(*)::integer from public.drops d
      where d.author_id = p_uid and d.source = 'user' and d.status <> 'removed' and d.counts_for_credit
    )
    else 0
  end;
$$;

-- Founding Locals and map campaigns use this predicate. A soft-flagged drop stays
-- on the map and does not take a founding slot or move a campaign total.
create or replace function private.drop_counts_in_region(
  p_drop public.drops,
  p_region public.regions,
  p_from timestamptz,
  p_until timestamptz
)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select p_drop.counts_for_credit
    and p_drop.source = 'user'::public.drop_source
    and p_drop.author_id is not null
    and p_drop.status in ('active'::public.drop_status, 'confirmed'::public.drop_status)
    and (p_drop.expires_at is null or p_drop.expires_at > now())
    and (p_from is null or p_drop.created_at >= p_from)
    and (p_until is null or p_drop.created_at < p_until)
    and exists (
      select 1 from public.profiles p
      where p.id = p_drop.author_id and p.deleted_at is null
    )
    and extensions.st_x(p_drop.geog::extensions.geometry) between p_region.min_lng and p_region.max_lng
    and extensions.st_y(p_drop.geog::extensions.geometry) between p_region.min_lat and p_region.max_lat;
$$;

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
  v_card.view_count := p_drop.view_count;
  v_card.expires_at := p_drop.expires_at;
  v_card.last_confirmed_at := p_drop.last_confirmed_at;
  v_card.created_at := p_drop.created_at;
  v_card.location_trust := p_drop.location_trust;
  v_card.location_withheld := false;
  v_card.is_mine := p_viewer is not null
    and p_drop.author_id is not null
    and p_drop.author_id = p_viewer;
  v_card.source := p_drop.source;
  v_card.author_badges := '[]'::jsonb;

  if p_viewer is not null then
    select c.verdict into v_card.my_verdict
    from public.confirmations c
    where c.drop_id = p_drop.id and c.user_id = p_viewer and c.counts_for_credit;
  end if;

  if p_drop.author_id is not null
     and not (p_drop.display_anonymous and v_category.allow_anonymous_display) then
    v_card.author_handle := (select p.handle from public.profiles p where p.id = p_drop.author_id);
    v_card.author_badges := private.author_badges_json(p_drop.author_id, p_drop.geog);
  end if;

  select s.name, i.source_url, i.license, i.attribution
    into v_card.source_name, v_card.source_url, v_card.license, v_card.attribution
  from public.open_data_items i
  join public.open_data_sources s on s.slug = i.source_slug
  where i.drop_id = p_drop.id;

  if p_point is not null then
    v_distance := extensions.st_distance(p_drop.display_geog, p_point);
    v_card.distance_m := round(v_distance::numeric, 1)::double precision;
  end if;

  v_card.revealed := p_drop.source = 'open_data'
    or v_card.is_mine
    or (v_distance is not null and v_distance <= p_drop.reveal_radius_m);

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
-- ---------------------------------------------------------------------------
-- Reveal. A spoofed "I'm here" does not unlock someone else's drop.
-- ---------------------------------------------------------------------------
drop function public.drop_detail(uuid, double precision, double precision);

create function public.drop_detail(
  p_drop_id uuid,
  p_lat double precision default null,
  p_lng double precision default null,
  p_integrity jsonb default null
)
returns public.drop_card
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_drop public.drops;
  v_point extensions.geography;
  v_decision jsonb;
  v_card public.drop_card;
  v_credit boolean := true;
  v_withhold boolean := false;
begin
  select * into v_drop from public.drops d where d.id = p_drop_id;
  if not found or not private.can_view_drop(v_drop, v_uid) then
    raise exception 'drop_not_found' using errcode = 'P0002';
  end if;

  if p_lat is not null or p_lng is not null then
    v_point := private.make_point(p_lat, p_lng);
    if v_uid is not null then
      v_decision := private.location_decision(v_uid, 'reveal', v_point, p_integrity);
      v_credit := coalesce((v_decision ->> 'credit')::boolean, true);
    end if;
  end if;

  v_card := private.to_drop_card(v_drop, v_uid, v_point);
  v_withhold := not v_credit
    and not v_card.is_mine
    and v_drop.source is distinct from 'open_data'
    and v_card.distance_m is not null
    and v_card.distance_m <= v_drop.reveal_radius_m;

  if v_withhold then
    perform private.record_location_flag(v_uid, 'reveal', v_drop.id, v_decision -> 'reasons');
    v_card.revealed := false;
    v_card.body := null;
    v_card.attributes := null;
    v_card.tags := null;
    v_card.media := null;
    v_card.location_withheld := true;
  else
    v_drop.view_count := private.record_drop_view(v_drop.id, v_drop.author_id);
    v_card.view_count := v_drop.view_count;
  end if;
  return v_card;
end;
$$;

drop function public.confirm_drop(uuid, public.confirmation_verdict, double precision, double precision, text);

create function public.confirm_drop(
  p_drop_id uuid,
  p_verdict public.confirmation_verdict,
  p_lat double precision,
  p_lng double precision,
  p_note text default null,
  p_integrity jsonb default null
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
  v_existing public.confirmations;
  v_point extensions.geography;
  v_distance double precision;
  v_limit integer;
  v_recent integer;
  v_decision jsonb;
  v_credit boolean;
  v_reasons jsonb;
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
  select count(*) into v_recent
  from public.confirmations c
  where c.user_id = v_uid
    and c.drop_id <> v_drop.id
    and c.updated_at > now() - interval '1 hour';
  if v_recent >= coalesce(v_limit, 0) then
    raise exception 'rate_limited' using errcode = 'PT429',
      detail = format('tier %s allows %s confirmations per hour', v_profile.tier, coalesce(v_limit, 0));
  end if;

  v_decision := private.location_decision(v_uid, 'confirm_drop', v_point, p_integrity);
  v_credit := coalesce((v_decision ->> 'credit')::boolean, true);
  v_reasons := coalesce(v_decision -> 'reasons', '[]'::jsonb);

  select * into v_existing from public.confirmations c
  where c.drop_id = v_drop.id and c.user_id = v_uid;

  -- A later spoof must not erase an answer that already counted.
  if not v_credit and v_existing.counts_for_credit is true then
    perform private.record_location_flag(v_uid, 'confirm_drop', v_drop.id, v_reasons);
    return jsonb_build_object(
      'drop_id', v_drop.id,
      'verdict', v_existing.verdict,
      'status', v_drop.status,
      'is_confirmed', v_drop.status = 'confirmed',
      'confirm_count', v_drop.confirm_count,
      'deny_count', v_drop.deny_count,
      'expires_at', v_drop.expires_at,
      'distance_m', v_existing.distance_m,
      'counts_for_credit', false,
      'reasons', v_reasons
    );
  end if;

  insert into public.confirmations (drop_id, user_id, verdict, note, distance_m, counts_for_credit)
  values (v_drop.id, v_uid, p_verdict, nullif(btrim(p_note), ''), round(v_distance)::integer, v_credit)
  on conflict (drop_id, user_id) do update
    set verdict = excluded.verdict,
        note = excluded.note,
        distance_m = excluded.distance_m,
        counts_for_credit = excluded.counts_for_credit;

  if not v_credit then
    perform private.record_location_flag(v_uid, 'confirm_drop', v_drop.id, v_reasons);
  elsif p_verdict = 'still_true' then
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
    'distance_m', round(v_distance)::integer,
    'counts_for_credit', v_credit,
    'reasons', v_reasons
  );
end;
$$;

drop function public.check_in(double precision, double precision, uuid);

create function public.check_in(
  p_lat double precision,
  p_lng double precision,
  p_walk_id uuid default null,
  p_integrity jsonb default null
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_point extensions.geography;
  v_decision jsonb;
  v_credit boolean := true;
  v_reasons jsonb := '[]'::jsonb;
  v_added integer := 0;
  v_count integer;
  v_just boolean := false;
  v_walk jsonb;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  if not exists (select 1 from public.profiles p where p.id = v_uid and p.deleted_at is null) then
    raise exception 'account_unavailable' using errcode = '42501';
  end if;

  v_point := private.make_point(p_lat, p_lng);
  v_decision := private.location_decision(v_uid, 'walk_check_in', v_point, p_integrity);
  v_credit := coalesce((v_decision ->> 'credit')::boolean, true);
  v_reasons := coalesce(v_decision -> 'reasons', '[]'::jsonb);

  if p_walk_id is not null and not exists (select 1 from public.walks w where w.id = p_walk_id and w.active) then
    raise exception 'walk_not_found' using errcode = 'P0002';
  end if;

  if v_credit then
    v_added := private.record_place_visits(v_uid, v_point);
    if p_walk_id is not null then
      v_just := private.mark_walk_stops(v_uid, p_walk_id, v_point);
    end if;
  else
    perform private.record_location_flag(v_uid, 'walk_check_in', p_walk_id, v_reasons);
  end if;

  select count(*)::integer into v_count from public.place_visits v where v.user_id = v_uid;
  if p_walk_id is not null then
    v_walk := private.walk_json(p_walk_id, v_uid, v_point);
  end if;

  return jsonb_build_object(
    'visit_count', v_count,
    'visits_added', v_added,
    'quests', private.quests_json(v_uid),
    'walk', v_walk,
    'walk_just_completed', v_just,
    'counts_for_credit', v_credit,
    'reasons', v_reasons
  );
end;
$$;

-- Recent soft flags. No coordinates. Moderators only. Nothing here suspends anyone.
create function public.location_integrity_queue(p_limit integer default 50)
returns table (
  id uuid,
  user_id uuid,
  handle text,
  action text,
  target_id uuid,
  reasons text[],
  created_at timestamptz
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_limit integer := least(greatest(coalesce(p_limit, 50), 1), 200);
begin
  if not private.is_moderator() then
    raise exception 'not_moderator' using errcode = '42501';
  end if;
  return query
  select f.id, f.user_id, p.handle, f.action, f.target_id, f.reasons, f.created_at
  from public.location_integrity_flags f
  join public.profiles p on p.id = f.user_id
  where f.expires_at > now()
    and p.deleted_at is null
    and f.user_id is distinct from auth.uid()
  order by f.created_at desc
  limit v_limit;
end;
$$;

-- Same rule as the columns. A person may ask about their own rows; a moderator may ask about anyone.
create function public.counts_for_location_credit(
  p_action text,
  p_user_id uuid,
  p_target_id uuid
)
returns boolean
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  if auth.uid() is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  if auth.uid() is distinct from p_user_id and not private.is_moderator() then
    raise exception 'not_allowed' using errcode = '42501';
  end if;
  return case p_action
    when 'create_drop' then coalesce((
      select d.counts_for_credit from public.drops d
      where d.id = p_target_id and d.author_id = p_user_id and d.source = 'user'
    ), false)
    when 'confirm_drop' then coalesce((
      select c.counts_for_credit from public.confirmations c
      where c.drop_id = p_target_id and c.user_id = p_user_id and c.verdict = 'still_true'
    ), false)
    when 'walk_check_in' then exists (
      select 1 from public.place_visits v
      where v.user_id = p_user_id and v.drop_id = p_target_id
    )
    else false
  end;
end;
$$;

drop function public.create_drop(text, double precision, double precision, text, text, jsonb, text[], integer, timestamptz, boolean, jsonb, boolean);
create function public.create_drop(
  p_category text,
  p_lat double precision,
  p_lng double precision,
  p_title text,
  p_body text default null,
  p_attributes jsonb default '{}'::jsonb,
  p_tags text[] default '{}',
  p_reveal_radius_m integer default null,
  p_expires_at timestamptz default null,
  p_display_anonymous boolean default false,
  p_media jsonb default '[]'::jsonb,
  p_acknowledge_guidelines boolean default false,
  p_integrity jsonb default null
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
  v_category public.categories;
  v_limits public.tier_limits;
  v_point extensions.geography;
  v_display extensions.geography;
  v_title text := btrim(coalesce(p_title, ''));
  v_body text := nullif(btrim(coalesce(p_body, '')), '');
  v_attributes jsonb := coalesce(p_attributes, '{}'::jsonb);
  v_media jsonb := coalesce(p_media, '[]'::jsonb);
  v_tags text[];
  v_reveal integer;
  v_cap interval;
  v_expires timestamptz;
  v_explicit_expiry boolean := false;
  v_status public.drop_status;
  v_errors text[];
  v_last_hour integer;
  v_last_day integer;
  v_drop public.drops;
  v_duplicates jsonb;
  v_item jsonb;
  v_decision jsonb;
  v_credit boolean := true;
  v_trust text := 'standard';
  v_new_id uuid;
begin
  -- Who is posting
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

  -- Category and who may post in it
  select * into v_category from public.categories c where c.slug = p_category and c.active;
  if not found then
    raise exception 'category_not_found' using errcode = '22023', detail = coalesce(p_category, 'null');
  end if;
  if v_profile.tier < v_category.min_tier_to_post then
    raise exception 'tier_too_low' using errcode = '42501',
      detail = format('%s requires tier %s', v_category.slug, v_category.min_tier_to_post);
  end if;
  if v_category.sensitive and not coalesce(p_acknowledge_guidelines, false) then
    raise exception 'guidelines_not_acknowledged' using errcode = '22023', hint = v_category.posting_notice;
  end if;

  -- Place, text, tags
  v_point := private.make_point(p_lat, p_lng);

  if char_length(v_title) not between 1 and 120 then
    raise exception 'invalid_title' using errcode = '22023', detail = 'title must be 1..120 characters';
  end if;
  if char_length(v_body) > 2000 then
    raise exception 'invalid_body' using errcode = '22023', detail = 'body must be at most 2000 characters';
  end if;

  select coalesce(array_agg(distinct lower(btrim(t))), '{}') into v_tags
  from unnest(coalesce(p_tags, '{}')) as t
  where btrim(t) <> '';
  if cardinality(v_tags) > 10
     or exists (select 1 from unnest(v_tags) as t where t !~ '^[a-z0-9][a-z0-9_-]{0,31}$') then
    raise exception 'invalid_tags' using errcode = '22023',
      detail = 'at most 10 tags of 1..32 characters: a-z, 0-9, _ and -';
  end if;

  -- Attributes against the category's JSON Schema, plus cross-field checks
  if jsonb_typeof(v_attributes) <> 'object' then
    raise exception 'invalid_attributes' using errcode = '22023', detail = 'attributes must be a JSON object';
  end if;
  if not extensions.jsonb_matches_schema(v_category.attributes_schema::json, v_attributes) then
    v_errors := extensions.jsonschema_validation_errors(v_category.attributes_schema::json, v_attributes::json);
    raise exception 'invalid_attributes' using errcode = '22023', detail = array_to_string(v_errors, '; ');
  end if;
  if v_attributes ? 'starts_at' and v_attributes ? 'ends_at'
     and (v_attributes ->> 'ends_at')::timestamptz < (v_attributes ->> 'starts_at')::timestamptz then
    raise exception 'invalid_attributes' using errcode = '22023', detail = 'ends_at is before starts_at';
  end if;
  if v_attributes ? 'occurred_at' then
    if (v_attributes ->> 'occurred_at')::timestamptz > now() + interval '5 minutes' then
      raise exception 'invalid_attributes' using errcode = '22023', detail = 'occurred_at is in the future';
    end if;
    if v_category.max_ttl is not null
       and (v_attributes ->> 'occurred_at')::timestamptz < now() - v_category.max_ttl then
      raise exception 'invalid_attributes' using errcode = '22023',
        detail = format('occurred_at is older than %s', v_category.max_ttl);
    end if;
  end if;

  perform private.assert_content_allowed(v_category.content_filters, array[v_title, v_body]);

  if coalesce(p_display_anonymous, false) and not v_category.allow_anonymous_display then
    raise exception 'anonymous_not_allowed' using errcode = '22023', detail = v_category.slug;
  end if;

  -- Reveal radius: authors may tighten it, never widen it past the category max
  v_reveal := coalesce(p_reveal_radius_m, v_category.default_reveal_m);
  if v_reveal < greatest(10, v_category.location_precision_m) or v_reveal > v_category.max_reveal_m then
    raise exception 'invalid_reveal_radius' using errcode = '22023',
      detail = format('allowed %s..%s m', greatest(10, v_category.location_precision_m), v_category.max_reveal_m);
  end if;

  -- Lifetime
  v_cap := case
    when v_profile.tier = 'new' and v_category.max_ttl_new is not null then v_category.max_ttl_new
    else v_category.max_ttl
  end;
  if v_category.expires_at_attribute is not null and v_attributes ? v_category.expires_at_attribute then
    v_expires := (v_attributes ->> v_category.expires_at_attribute)::timestamptz;
    v_explicit_expiry := true;
  end if;
  if p_expires_at is not null then
    v_expires := least(coalesce(v_expires, p_expires_at), p_expires_at);
    v_explicit_expiry := true;
  end if;
  if v_expires is null and v_category.default_ttl is not null then
    v_expires := now() + v_category.default_ttl;
  end if;
  if v_explicit_expiry and v_expires <= now() then
    raise exception 'expires_in_past' using errcode = '22023';
  end if;
  if v_cap is not null then
    if v_expires is null then
      v_expires := now() + v_cap;
    elsif v_expires > now() + v_cap then
      if v_explicit_expiry then
        raise exception 'expires_too_far' using errcode = '22023',
          detail = format('%s allows at most %s ahead', v_category.slug, v_cap);
      end if;
      v_expires := now() + v_cap;
    end if;
  end if;

  -- Media refs: objects pointing at the caller's own Storage folder
  if jsonb_typeof(v_media) <> 'array' then
    raise exception 'invalid_media' using errcode = '22023', detail = 'media must be a JSON array';
  end if;
  for v_item in select value from jsonb_array_elements(v_media) loop
    if jsonb_typeof(v_item) <> 'object'
       or coalesce(v_item ->> 'storage_path', '') = ''
       or split_part(v_item ->> 'storage_path', '/', 1) <> v_uid::text
       or coalesce(v_item ->> 'mime_type', '') = ''
       or not (coalesce(v_item ->> 'media_kind', '') = any (enum_range(null::public.media_kind)::text[])) then
      raise exception 'invalid_media' using errcode = '22023',
        detail = 'each item needs media_kind, mime_type, and a storage_path under "<your user id>/"';
    end if;
  end loop;
  if v_category.requires_photo
     and not exists (select 1 from jsonb_array_elements(v_media) e where e.value ->> 'media_kind' = 'image') then
    raise exception 'photo_required' using errcode = '22023', detail = v_category.slug;
  end if;

  -- Moderation and rate limits
  v_status := case
    when private.needs_premod(v_category, v_profile.tier) then 'pending_review'::public.drop_status
    else 'active'::public.drop_status
  end;

  perform pg_advisory_xact_lock(hashtextextended('xhere.create_drop:' || v_uid::text, 0));
  select * into v_limits from public.tier_limits tl where tl.tier = v_profile.tier;
  select count(*) filter (where d.created_at > now() - interval '1 hour'), count(*)
    into v_last_hour, v_last_day
  from public.drops d
  where d.author_id = v_uid and d.created_at > now() - interval '1 day';
  if v_last_hour >= coalesce(v_limits.drops_per_hour, 0) or v_last_day >= coalesce(v_limits.drops_per_day, 0) then
    raise exception 'rate_limited' using errcode = 'PT429',
      detail = format('tier %s allows %s drops per hour and %s per day',
        v_profile.tier, coalesce(v_limits.drops_per_hour, 0), coalesce(v_limits.drops_per_day, 0));
  end if;

  -- Blur, duplicate hint, insert
  v_display := private.fuzz_point(v_point, v_category.location_precision_m);

  select coalesce(jsonb_agg(x.short_id), '[]'::jsonb) into v_duplicates
  from (
    select d.short_id
    from public.drops d
    where d.category_id = v_category.id
      and extensions.st_dwithin(d.display_geog, v_display, 50)
      and extensions.similarity(d.title, v_title) >= 0.3
      and private.can_view_drop(d, v_uid)
    order by d.created_at desc
    limit 5
  ) as x;

  v_new_id := gen_random_uuid();
  v_decision := private.location_decision(v_uid, 'create_drop', v_point, p_integrity);
  v_credit := coalesce((v_decision ->> 'credit')::boolean, true);
  v_trust := case when v_credit then 'standard' else 'reduced' end;
  if not v_credit then
    perform private.record_location_flag(v_uid, 'create_drop', v_new_id, v_decision -> 'reasons');
  end if;

  insert into public.drops (
    id, author_id, category_id, category_version, source, title, body, attributes, tags,
    geog, display_geog, reveal_radius_m, status, display_anonymous, expires_at,
    location_trust, counts_for_credit
  ) values (
    v_new_id, v_uid, v_category.id, v_category.version, 'user', v_title, v_body, v_attributes, v_tags,
    v_point, v_display, v_reveal, v_status, coalesce(p_display_anonymous, false), v_expires,
    v_trust, v_credit
  )
  returning * into v_drop;

  insert into public.drop_media (
    drop_id, owner_id, storage_bucket, storage_path, media_kind, mime_type,
    byte_size, width, height, duration_s, sort, taken_here
  )
  select v_drop.id, v_uid,
         coalesce(e.value ->> 'storage_bucket', 'drop-media'),
         e.value ->> 'storage_path',
         (e.value ->> 'media_kind')::public.media_kind,
         e.value ->> 'mime_type',
         (e.value ->> 'byte_size')::bigint,
         (e.value ->> 'width')::integer,
         (e.value ->> 'height')::integer,
         (e.value ->> 'duration_s')::numeric,
         (e.ord - 1)::smallint,
         private.photo_taken_here(v_point, e.value, v_credit, p_integrity)
  from jsonb_array_elements(v_media) with ordinality as e(value, ord);

  return jsonb_build_object(
    'id', v_drop.id,
    'short_id', v_drop.short_id,
    'status', v_drop.status,
    'category', v_category.slug,
    'category_version', v_drop.category_version,
    'reveal_radius_m', v_drop.reveal_radius_m,
    'expires_at', v_drop.expires_at,
    'lat', extensions.st_y(v_drop.display_geog::extensions.geometry),
    'lng', extensions.st_x(v_drop.display_geog::extensions.geometry),
    'location_precision_m', v_category.location_precision_m,
    'posting_notice', v_category.posting_notice,
    'possible_duplicates', v_duplicates,
    'counts_for_credit', v_credit,
    'location_trust', v_trust,
    'reasons', coalesce(v_decision -> 'reasons', '[]'::jsonb)
  );
end;
$$;

-- ---------------------------------------------------------------------------
-- Grants. New signatures replace the ones dropped above.
-- ---------------------------------------------------------------------------
revoke execute on function
  private.location_decision(uuid, text, extensions.geography, jsonb),
  private.record_location_flag(uuid, text, uuid, jsonb),
  private.photo_taken_here(extensions.geography, jsonb, boolean, jsonb),
  private.purge_location_integrity()
from public, anon, authenticated;

revoke execute on function
  public.drop_detail(uuid, double precision, double precision, jsonb),
  public.confirm_drop(uuid, public.confirmation_verdict, double precision, double precision, text, jsonb),
  public.check_in(double precision, double precision, uuid, jsonb),
  public.create_drop(text, double precision, double precision, text, text, jsonb, text[], integer, timestamptz, boolean, jsonb, boolean, jsonb),
  public.location_integrity_queue(integer),
  public.counts_for_location_credit(text, uuid, uuid)
from public, anon, authenticated;

grant execute on function
  public.drop_detail(uuid, double precision, double precision, jsonb),
  public.confirm_drop(uuid, public.confirmation_verdict, double precision, double precision, text, jsonb),
  public.check_in(double precision, double precision, uuid, jsonb),
  public.create_drop(text, double precision, double precision, text, text, jsonb, text[], integer, timestamptz, boolean, jsonb, boolean, jsonb),
  public.location_integrity_queue(integer),
  public.counts_for_location_credit(text, uuid, uuid)
to authenticated, service_role;

select cron.schedule('xhere-purge-location-integrity', '*/15 * * * *', 'select private.purge_location_integrity()');

-- The caller's own flags, without coordinates, on the existing export document.
do $export$
declare
  v_sql text := pg_get_functiondef('public.export_my_data()'::regprocedure);
  v_needle constant text := '''moderation_of_my_content'', coalesce((';
  v_insert constant text := '''location_flags'', coalesce((
      select jsonb_agg(jsonb_build_object(
          ''action'', f.action,
          ''target_id'', f.target_id,
          ''reasons'', to_jsonb(f.reasons),
          ''created_at'', f.created_at
        ) order by f.created_at)
      from public.location_integrity_flags f
      where f.user_id = v_uid and f.expires_at > now()
    ), ''[]''::jsonb),
    ''moderation_of_my_content'', coalesce((';
begin
  if position('location_flags' in v_sql) > 0 then
    return;
  end if;
  if position(v_needle in v_sql) = 0 then
    raise exception 'export_my_data anchor missing';
  end if;
  execute replace(v_sql, v_needle, v_insert);
end;
$export$;
