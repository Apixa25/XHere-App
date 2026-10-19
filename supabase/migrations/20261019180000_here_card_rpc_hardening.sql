-- Here Card abuse limits (advisor 0029 on claim_nominatim_slot and remember_place_label).
--
-- Both RPCs stay security definer and stay granted to authenticated, because the
-- signed-in app calls them. They are intentional public API (advisor option 3):
-- the tables stay closed, and the functions now limit what a caller can do.
-- Anon still has no execute. here_card stays signed-in only. The Android app
-- shows the map only after sign-in and onboarding (Gate.SignedOut is the auth
-- screen). The website is a landing page and share teasers, not a browsable
-- map (PLAN §1.5). drops_in_view and drop_teaser remain the signed-out reads.
--
-- Not moved behind an Edge Function. The phone calls Nominatim itself after a
-- true claim, on the caller's own network, at no charge. An Edge Function would
-- send every lookup through the project's shared egress and change that flow.
--
-- claim_nominatim_slot still allows one lookup every 1.1 seconds for the whole
-- project. A signed-in user who wins a slot cannot win another for 10 seconds,
-- so one account cannot hold the gate. A miss (the global slot is busy) does
-- not start that personal pause. The rows store a user id and a timestamp, not
-- a coordinate.
--
-- remember_place_label still derives the cell with round(coord, 3). That cell
-- has to sit within half of 0.001° of the coordinates, which is the same grid.
-- The name and address have length and character limits. Attribution is still
-- stamped here. The first write still wins for 90 days. A caller may change a
-- row at most once every 10 seconds and 30 times an hour. A repeat of a fresh
-- cell does not count. No coordinate is stored on the quota row.

alter table public.place_labels
  add constraint place_labels_on_grid check (
    cell_lat between -90 and 90
    and cell_lng between -180 and 180
  );

create table private.nominatim_user_gate (
  user_id uuid primary key references public.profiles (id) on delete cascade,
  next_allowed_at timestamptz not null default '-infinity'::timestamptz
);
comment on table private.nominatim_user_gate is
  'Per-user Nominatim pause. One successful claim every 10 seconds. No coordinate is stored.';

alter table private.nominatim_user_gate enable row level security;
revoke all on table private.nominatim_user_gate from public, anon, authenticated;

create table private.place_label_quota (
  user_id uuid primary key references public.profiles (id) on delete cascade,
  next_allowed_at timestamptz not null default '-infinity'::timestamptz,
  window_started_at timestamptz not null default now(),
  writes_in_window integer not null default 0 check (writes_in_window >= 0)
);
comment on table private.place_label_quota is
  'Per-user place-label write cap. One mutating write every 10 seconds, 30 per hour. No coordinate is stored.';

alter table private.place_label_quota enable row level security;
revoke all on table private.place_label_quota from public, anon, authenticated;

-- Letters and digits from any language, spaces, and the punctuation Nominatim
-- uses in a place name. Control characters, markup, and http links are refused.
create function private.place_label_text_ok(p_value text, p_max integer)
returns boolean
language sql
immutable
set search_path = ''
as $$
  select p_value is not null
    and pg_catalog.char_length(p_value) between 1 and p_max
    and p_value !~ '[[:cntrl:]]'
    and p_value ~ '[[:alnum:]]'
    and p_value !~* 'https?://'
    and p_value !~ '[^[:alnum:][:space:].,''’‘“”«»/()&#:+;°ºª_·–—-]';
$$;

create function private.consume_place_label_write(p_uid uuid)
returns void
language plpgsql
volatile
set search_path = ''
as $$
declare
  v_next timestamptz;
  v_window timestamptz;
  v_writes integer;
begin
  insert into private.place_label_quota (user_id, next_allowed_at, window_started_at, writes_in_window)
  values (p_uid, '-infinity', pg_catalog.clock_timestamp(), 0)
  on conflict (user_id) do nothing;

  select q.next_allowed_at, q.window_started_at, q.writes_in_window
    into v_next, v_window, v_writes
  from private.place_label_quota q
  where q.user_id = p_uid
  for update;
  if not found then
    raise exception 'place_label_quota_missing' using errcode = 'P0002';
  end if;

  if v_window < pg_catalog.clock_timestamp() - interval '1 hour' then
    v_window := pg_catalog.clock_timestamp();
    v_writes := 0;
  end if;

  if v_next > pg_catalog.clock_timestamp() or v_writes >= 30 then
    raise exception 'rate_limited' using errcode = 'PT429',
      detail = 'at most one place-label write every 10 seconds and 30 per hour';
  end if;

  update private.place_label_quota
  set next_allowed_at = pg_catalog.clock_timestamp() + interval '10 seconds',
      window_started_at = v_window,
      writes_in_window = v_writes + 1
  where user_id = p_uid;
end;
$$;

revoke execute on function
  private.place_label_text_ok(text, integer),
  private.consume_place_label_write(uuid)
from public, anon, authenticated;

create or replace function public.remember_place_label(
  p_lat double precision,
  p_lng double precision,
  p_name text,
  p_address text default null
)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_name text := nullif(btrim(p_name), '');
  v_address text := nullif(btrim(p_address), '');
  v_cell_lat numeric(7, 3);
  v_cell_lng numeric(7, 3);
  v_existing timestamptz;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  if not exists (
    select 1 from public.profiles p where p.id = v_uid and p.deleted_at is null
  ) then
    raise exception 'account_unavailable' using errcode = '42501';
  end if;
  perform private.make_point(p_lat, p_lng);

  -- The only row this call may touch. round(..., 3) is the 0.001° grid, and
  -- numeric(7, 3) cannot store a finer key. The half-step check keeps the stored
  -- cell on that grid of these coordinates (half a cell is 0.0005°).
  v_cell_lat := round(p_lat::numeric, 3);
  v_cell_lng := round(p_lng::numeric, 3);
  if abs(p_lat - v_cell_lat::double precision) > 0.0005::double precision + 1e-8
     or abs(p_lng - v_cell_lng::double precision) > 0.0005::double precision + 1e-8 then
    raise exception 'invalid_place' using errcode = '22023',
      detail = 'cell must be the 0.001 degree grid of the coordinates';
  end if;

  if not private.place_label_text_ok(v_name, 160) then
    raise exception 'invalid_place' using errcode = '22023',
      detail = 'place name must be 1..160 letters, numbers, spaces, or place punctuation';
  end if;
  if v_address is not null and not private.place_label_text_ok(v_address, 300) then
    raise exception 'invalid_place' using errcode = '22023',
      detail = 'address must be at most 300 letters, numbers, spaces, or place punctuation';
  end if;

  select l.fetched_at into v_existing
  from public.place_labels l
  where l.cell_lat = v_cell_lat and l.cell_lng = v_cell_lng;

  -- A fresh row is kept. Repeating it must not burn the writer's quota.
  if v_existing is null or v_existing < now() - interval '90 days' then
    perform private.consume_place_label_write(v_uid);
  end if;

  insert into public.place_labels (cell_lat, cell_lng, name, address, attribution, license, provider)
  values (
    v_cell_lat, v_cell_lng, v_name, v_address,
    '© OpenStreetMap contributors', 'ODbL-1.0', 'nominatim'
  )
  on conflict (cell_lat, cell_lng) do update
    set name = excluded.name,
        address = excluded.address,
        attribution = excluded.attribution,
        license = excluded.license,
        provider = excluded.provider,
        fetched_at = now()
    where public.place_labels.fetched_at < now() - interval '90 days';
end;
$$;

create or replace function public.claim_nominatim_slot()
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_user_next timestamptz;
  v_global_next timestamptz;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  if not exists (
    select 1 from public.profiles p where p.id = v_uid and p.deleted_at is null
  ) then
    raise exception 'account_unavailable' using errcode = '42501';
  end if;

  insert into private.nominatim_user_gate (user_id, next_allowed_at)
  values (v_uid, '-infinity')
  on conflict (user_id) do nothing;

  -- User row first, then the single global row, so two callers cannot deadlock.
  select g.next_allowed_at into v_user_next
  from private.nominatim_user_gate g
  where g.user_id = v_uid
  for update;
  if not found then
    raise exception 'nominatim_user_gate_missing' using errcode = 'P0002';
  end if;

  if v_user_next > clock_timestamp() then
    return false;
  end if;

  select g.next_allowed_at into v_global_next
  from private.nominatim_gate g
  where g.id = 1
  for update;
  if not found then
    raise exception 'nominatim_gate_missing' using errcode = 'P0002';
  end if;

  if v_global_next > clock_timestamp() then
    return false;
  end if;

  update private.nominatim_gate
  set next_allowed_at = clock_timestamp() + interval '1100 milliseconds'
  where id = 1;

  update private.nominatim_user_gate
  set next_allowed_at = clock_timestamp() + interval '10 seconds'
  where user_id = v_uid;

  return true;
end;
$$;

comment on function public.remember_place_label(double precision, double precision, text, text) is
  'Caches a Nominatim place name on the 0.001° cell of the coordinates. Attribution is stamped here. First write wins for 90 days. One mutating write per user every 10 seconds, 30 per hour.';

comment on function public.claim_nominatim_slot() is
  'True when this caller may hit Nominatim once. The project allows one lookup every 1.1 seconds. The same user cannot take another slot for 10 seconds. A busy global slot does not start that pause.';

revoke execute on function
  public.remember_place_label(double precision, double precision, text, text),
  public.claim_nominatim_slot()
from public, anon, authenticated;

grant execute on function
  public.remember_place_label(double precision, double precision, text, text),
  public.claim_nominatim_slot()
to authenticated, service_role;
