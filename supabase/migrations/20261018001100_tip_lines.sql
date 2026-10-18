-- M7: incident reports (crime) only where a police tip line is configured.
--
-- App Review 1.7: apps for reporting alleged criminal activity must involve local
-- law enforcement and can only be offered where that involvement is active.
-- The app hides the option outside these areas (shared SafetyDirectory); this
-- makes the server refuse too, so an old or modified client cannot post one.
-- M16b moves tip lines into the per-region config (OPEN-DATA-PLAYBOOK §3 `tip_lines`).

create table private.tip_lines (
  region_slug text primary key,
  name text not null,
  phone text not null,
  url text,
  area extensions.geography(Polygon, 4326) not null,
  created_at timestamptz not null default now()
);
create index tip_lines_area_idx on private.tip_lines using gist (area);
comment on table private.tip_lines is
  'Law-enforcement tip lines by area. incident_report drops can only be created inside one of these areas.';

-- `region.bbox` for us-ny-chautauqua: [-79.77, 41.99, -79.05, 42.58].
insert into private.tip_lines (region_slug, name, phone, url, area) values
  ('us-ny-chautauqua',
   'Jamestown Police anonymous tip line',
   '(716) 483-8477',
   'https://www.jamestownny.gov/police-department/anonymous-tip-line/',
   extensions.st_geogfromtext('SRID=4326;POLYGON((-79.77 41.99, -79.05 41.99, -79.05 42.58, -79.77 42.58, -79.77 41.99))'));

create function private.drops_require_tip_line()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if exists (select 1 from public.categories c where c.id = new.category_id and c.slug = 'incident_report')
     and not exists (select 1 from private.tip_lines t where extensions.st_covers(t.area, new.geog)) then
    raise exception 'incident_reports_unavailable' using errcode = '42501',
      hint = 'Incident reports are only available where a local police tip line is set up. In an emergency call 911.';
  end if;
  return new;
end;
$$;

create trigger drops_require_tip_line before insert on public.drops
  for each row execute function private.drops_require_tip_line();

revoke execute on function private.drops_require_tip_line() from public;
