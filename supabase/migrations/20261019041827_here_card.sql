-- Here Card (research recommendation #3, PLAN §2 geocoding, §3 proximity reveal, §4.1).
--
-- Tap or long-press any spot, not only an existing pin. One read returns the
-- drops and public records within a short walk, still obeying reveal rules:
-- distance is from the tapped spot, but a user drop's body, attributes, media,
-- and tags stay hidden until the caller's own location is inside the reveal
-- radius. Public records stay readable, with the attribution already stored
-- on the card. This does not count a view. Browsing a place is not opening a drop.
--
-- Place names come from a free cache of OpenStreetMap Nominatim results
-- (https://operations.osmfoundation.org/policies/nominatim/). The database
-- does not call Nominatim. The client may, at most once per 1.1 seconds
-- worldwide (claim_nominatim_slot), and only for a cell that is not cached.
-- Results are rounded to 0.001° (~111 m) so nearby taps share one lookup.
-- Attribution is stamped here. A client cannot replace it.
-- Nothing in this migration is tied to a city or country.

-- ---------------------------------------------------------------------------
-- Cached place names. Not a user location trail: no user id is stored.
-- ---------------------------------------------------------------------------
create table public.place_labels (
  cell_lat numeric(7, 3) not null,
  cell_lng numeric(7, 3) not null,
  name text not null check (char_length(btrim(name)) between 1 and 160),
  address text check (address is null or char_length(address) between 1 and 300),
  attribution text not null,
  license text not null,
  provider text not null,
  fetched_at timestamptz not null default now(),
  primary key (cell_lat, cell_lng)
);
comment on table public.place_labels is
  'Reverse-geocoded place name for a 0.001° cell. OpenStreetMap data, ODbL, cached so the app does not repeat Nominatim lookups.';

alter table public.place_labels enable row level security;
revoke all on table public.place_labels from public, anon, authenticated;

-- One row. claim_nominatim_slot locks it so the whole project stays under
-- Nominatim's absolute maximum of 1 request per second.
create table private.nominatim_gate (
  id smallint primary key default 1 check (id = 1),
  next_allowed_at timestamptz not null default '-infinity'::timestamptz
);
insert into private.nominatim_gate (id) values (1);
comment on table private.nominatim_gate is
  'Global Nominatim throttle. One reverse lookup every 1.1 seconds, for every user of this database.';

alter table private.nominatim_gate enable row level security;
revoke all on table private.nominatim_gate from public, anon, authenticated;

-- ---------------------------------------------------------------------------
-- here_card: visible drops near the tap. Reveal uses the viewer point.
-- ---------------------------------------------------------------------------
create function public.here_card(
  p_lat double precision,
  p_lng double precision,
  p_viewer_lat double precision default null,
  p_viewer_lng double precision default null,
  p_radius_m integer default 300,
  p_limit integer default 40
)
returns setof public.drop_card
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_tap extensions.geography;
  v_viewer extensions.geography;
  v_uid uuid := auth.uid();
  v_limit integer;
  v_drop public.drops;
  v_card public.drop_card;
begin
  v_tap := private.make_point(p_lat, p_lng);
  if p_viewer_lat is null and p_viewer_lng is null then
    v_viewer := null;
  elsif p_viewer_lat is null or p_viewer_lng is null then
    raise exception 'invalid_location' using errcode = '22023',
      detail = 'viewer lat and lng must both be set or both be null';
  else
    v_viewer := private.make_point(p_viewer_lat, p_viewer_lng);
  end if;
  if p_radius_m is null or p_radius_m not between 25 and 1000 then
    raise exception 'invalid_radius' using errcode = '22023',
      detail = 'radius must be 25..1000 m';
  end if;
  v_limit := least(greatest(coalesce(p_limit, 40), 1), 80);

  for v_drop in
    select d.*
    from public.drops d
    where extensions.st_dwithin(d.display_geog, v_tap, p_radius_m)
      and private.can_view_drop(d, v_uid)
    order by extensions.st_distance(d.display_geog, v_tap), d.created_at desc
    limit v_limit
  loop
    -- to_drop_card reveals from the viewer's location. Distance on the card
    -- is how far the drop is from the spot that was tapped.
    v_card := private.to_drop_card(v_drop, v_uid, v_viewer);
    v_card.distance_m := round(extensions.st_distance(v_drop.display_geog, v_tap)::numeric, 1)::double precision;
    return next v_card;
  end loop;
end;
$$;

comment on function public.here_card(double precision, double precision, double precision, double precision, integer, integer) is
  'Drops and public records near a tapped spot. User-drop bodies stay hidden until the viewer is inside the reveal radius. Does not count a view.';

-- ---------------------------------------------------------------------------
-- Place-name cache. Attribution is not a client argument.
-- ---------------------------------------------------------------------------
create function public.place_label(
  p_lat double precision,
  p_lng double precision
)
returns table (
  name text,
  address text,
  attribution text,
  license text,
  provider text
)
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  perform private.make_point(p_lat, p_lng);
  return query
  select l.name, l.address, l.attribution, l.license, l.provider
  from public.place_labels l
  where l.cell_lat = round(p_lat::numeric, 3)
    and l.cell_lng = round(p_lng::numeric, 3);
end;
$$;

create function public.remember_place_label(
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
  v_name text := nullif(btrim(p_name), '');
  v_address text := nullif(btrim(p_address), '');
  v_cell_lat numeric(7, 3);
  v_cell_lng numeric(7, 3);
begin
  if auth.uid() is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  if not exists (
    select 1 from public.profiles p where p.id = auth.uid() and p.deleted_at is null
  ) then
    raise exception 'account_unavailable' using errcode = '42501';
  end if;
  perform private.make_point(p_lat, p_lng);
  if v_name is null or char_length(v_name) > 160 or v_name ~ '[[:cntrl:]]' then
    raise exception 'invalid_place' using errcode = '22023',
      detail = 'place name must be 1..160 characters without control characters';
  end if;
  if v_address is not null and (char_length(v_address) > 300 or v_address ~ '[[:cntrl:]]') then
    raise exception 'invalid_place' using errcode = '22023',
      detail = 'address must be at most 300 characters without control characters';
  end if;
  v_cell_lat := round(p_lat::numeric, 3);
  v_cell_lng := round(p_lng::numeric, 3);
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

create function public.claim_nominatim_slot()
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_next timestamptz;
begin
  if auth.uid() is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  if not exists (
    select 1 from public.profiles p where p.id = auth.uid() and p.deleted_at is null
  ) then
    raise exception 'account_unavailable' using errcode = '42501';
  end if;
  select g.next_allowed_at into v_next
  from private.nominatim_gate g
  where g.id = 1
  for update;
  if v_next > clock_timestamp() then
    return false;
  end if;
  update private.nominatim_gate
  set next_allowed_at = clock_timestamp() + interval '1100 milliseconds'
  where id = 1;
  return true;
end;
$$;

comment on function public.claim_nominatim_slot() is
  'True when this caller may hit Nominatim once. False when another caller used the slot in the last 1.1 seconds.';

revoke execute on function
  public.here_card(double precision, double precision, double precision, double precision, integer, integer),
  public.place_label(double precision, double precision),
  public.remember_place_label(double precision, double precision, text, text),
  public.claim_nominatim_slot()
from public, anon, authenticated;

grant execute on function
  public.here_card(double precision, double precision, double precision, double precision, integer, integer),
  public.place_label(double precision, double precision),
  public.remember_place_label(double precision, double precision, text, text),
  public.claim_nominatim_slot()
to authenticated, service_role;
