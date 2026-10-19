-- Demo accounts are not real contributors.
--
-- PLAN §5 (profiles, badges) and the founding rules in
-- 20261019184521_founding_and_campaigns.sql. A seeded demo account's drops
-- stay on the map. They do not take a Founding Local rank, and they do not
-- add to a campaign total or a leaderboard. The flag is regional-agnostic:
-- any profile with is_demo is skipped, in every region.
--
-- There is no reputation-points scorer in the database yet (M11). Founding
-- and campaigns both go through private.drop_counts_in_region, so that one
-- predicate is the standing gate. A later score should skip is_demo the
-- same way.
--
-- Clients cannot write the column. service_role can. An admin uses
-- public.set_account_demo. The column is not granted to anon or authenticated,
-- and a trigger rejects a change from anyone else if a later grant is wider.
--
-- Ordered after 20261019233041_live_conditions.sql.

alter table public.profiles
  add column is_demo boolean not null default false;

comment on column public.profiles.is_demo is
  'True for a non-real account, such as a seeded demo. Drops stay on the map. Founding ranks, campaign totals, and leaderboards skip the account. Not writable by anon or authenticated. service_role may update the column. An admin calls set_account_demo.';

revoke update (is_demo) on public.profiles from public, anon, authenticated;
grant update (is_demo) on public.profiles to service_role;

-- ---------------------------------------------------------------------------
-- Who may change the flag. SECURITY INVOKER so current_user is the updater.
-- A security definer function would always see the owner and could not tell
-- a client apart from a migration.
-- ---------------------------------------------------------------------------
create function private.guard_profile_is_demo()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.is_demo is not distinct from old.is_demo then
    return new;
  end if;
  if current_user in ('postgres', 'supabase_admin', 'service_role')
     or coalesce(auth.role(), '') = 'service_role'
     or private.is_admin() then
    return new;
  end if;
  raise exception 'is_demo_locked' using errcode = '42501';
end;
$$;

create trigger profiles_guard_is_demo
  before update on public.profiles
  for each row execute function private.guard_profile_is_demo();

-- Profile edits (handle, display name) fire this trigger, so signed-in users
-- need to execute it. It returns the row unchanged unless is_demo moved, and
-- it is not a client RPC. private is not in the API schema list.
grant execute on function private.guard_profile_is_demo() to anon, authenticated, service_role;
revoke execute on function private.guard_profile_is_demo() from public;

-- ---------------------------------------------------------------------------
-- The standing gate. Same signature as the founding migration.
-- ---------------------------------------------------------------------------
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
  select p_drop.source = 'user'::public.drop_source
    and p_drop.author_id is not null
    and p_drop.status in ('active'::public.drop_status, 'confirmed'::public.drop_status)
    and (p_drop.expires_at is null or p_drop.expires_at > now())
    and (p_from is null or p_drop.created_at >= p_from)
    and (p_until is null or p_drop.created_at < p_until)
    and exists (
      select 1 from public.profiles p
      where p.id = p_drop.author_id
        and p.deleted_at is null
        and not p.is_demo
    )
    and extensions.st_x(p_drop.geog::extensions.geometry) between p_region.min_lng and p_region.max_lng
    and extensions.st_y(p_drop.geog::extensions.geometry) between p_region.min_lat and p_region.max_lat;
$$;

comment on function private.drop_counts_in_region(public.drops, public.regions, timestamptz, timestamptz) is
  'True when this drop is a real, public contribution inside the region. Demo accounts do not count. p_from is inclusive and p_until is exclusive. Null bounds mean all time (founding).';

-- After an account becomes demo, or stops being demo, founding ranks in the
-- regions they touched are recomputed. Campaign totals are read live.
create function private.sync_founding_when_demo_changes()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_region text;
begin
  if new.is_demo is not distinct from old.is_demo then
    return null;
  end if;

  for v_region in
    select s.region_id
    from (
      select r.id as region_id
      from public.regions r
      join public.region_founding f on f.region_id = r.id
      where exists (
        select 1
        from public.drops d
        where d.author_id = new.id
          and d.source = 'user'::public.drop_source
          and extensions.st_x(d.geog::extensions.geometry) between r.min_lng and r.max_lng
          and extensions.st_y(d.geog::extensions.geometry) between r.min_lat and r.max_lat
      )
      union
      select ub.region_id
      from public.user_badges ub
      join public.badges b on b.id = ub.badge_id
      where ub.user_id = new.id
        and b.slug = 'founding_local'
        and ub.region_id is not null
    ) s
  loop
    perform private.refresh_founders(v_region);
  end loop;
  return null;
end;
$$;

create trigger profiles_sync_founding_when_demo_changes
  after update of is_demo on public.profiles
  for each row execute function private.sync_founding_when_demo_changes();

-- ---------------------------------------------------------------------------
-- Admin path. The table grant stays closed, matching tier and role.
-- ---------------------------------------------------------------------------
create function public.set_account_demo(p_user_id uuid, p_demo boolean)
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
  if p_user_id is null or p_demo is null then
    raise exception 'invalid_demo_flag' using errcode = '22023';
  end if;
  if coalesce(auth.role(), '') <> 'service_role' and not private.is_admin() then
    raise exception 'not_authorized' using errcode = '42501';
  end if;
  update public.profiles
  set is_demo = p_demo
  where id = p_user_id;
  if not found then
    raise exception 'account_unavailable' using errcode = '42501';
  end if;
  return p_demo;
end;
$$;

comment on function public.set_account_demo(uuid, boolean) is
  'Marks or clears a non-real account. Admin or service_role only. Drops are not removed. Founding ranks recompute for the regions that account touched.';

revoke execute on function public.set_account_demo(uuid, boolean) from public, anon, authenticated;
grant execute on function public.set_account_demo(uuid, boolean) to authenticated, service_role;

revoke execute on function private.sync_founding_when_demo_changes() from public, anon, authenticated;
grant execute on function private.sync_founding_when_demo_changes() to service_role;

-- ---------------------------------------------------------------------------
-- Pilot rows. seed.sql uses the 4000-8000 ids. Guide, trusted, and regular
-- are the three that took founding ranks. demo_new is the fourth seeded
-- account and is marked too, so a later drop from that account cannot take
-- a slot. The 0000-0000 ids are the same three rank-holders if a row was
-- written that way. A missing id updates nothing.
-- ---------------------------------------------------------------------------
update public.profiles
set is_demo = true
where id in (
  'd0000000-0000-4000-8000-000000000001',
  'd0000000-0000-4000-8000-000000000002',
  'd0000000-0000-4000-8000-000000000003',
  'd0000000-0000-4000-8000-000000000004',
  'd0000000-0000-0000-0000-000000000001',
  'd0000000-0000-0000-0000-000000000002',
  'd0000000-0000-0000-0000-000000000003'
);

-- Ranks recompute after the flag and the predicate are both in place.
-- On a fresh database the region rows are still in the seed, so this is a
-- no-op there. On a database that already ran refresh_founders, demo holders
-- lose the badge and the next real person moves up.
do $refresh$
declare
  v_region text;
begin
  for v_region in
    select s.region_id
    from (
      select f.region_id from public.region_founding f
      union
      select ub.region_id
      from public.user_badges ub
      join public.badges b on b.id = ub.badge_id
      where b.slug = 'founding_local'
        and ub.region_id is not null
    ) s
  loop
    perform private.refresh_founders(v_region);
  end loop;
end;
$refresh$;
