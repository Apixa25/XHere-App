-- M11 reputation: tiers, points, and badges (PLAN §3 and §7, research #7).
--
-- Points live on profiles.points and move when something happens, so a map
-- request never scans confirmations. A nightly job turns the score into a
-- tier. The numbers are one row in reputation_rules, which the app can read.
--
-- What counts, for a user drop only (source = user, with an author):
--   * someone else says still true: 8, then 3, then 1, then 0 from that person
--   * someone else opens it: 2, then 1, for the first 3 of your drops they open
--   * you finish a walk: 12, once
--   * you finish a quest: 10, once
--   * someone else says not anymore: minus 6, then minus 2, then 0
--   * a moderator removes the drop: minus 15, and that drop's other points come off
-- Your own confirms and views never count. Imported open_data never counts.
-- Two people who only confirm each other score once, on the earlier direction.
-- profiles.is_demo does not earn, and a demo confirm or view does not move
-- anyone else's score. The drops stay on the map. A pinned demo tier stays.
--
-- spoof_flag (nullable) on confirmations, drop_views, and walk_progress, and
-- on quest_completions, is the M12 hook. private.reputation_signal_left_out
-- is the one predicate. Null counts. True is left out. False means it was
-- looked at and still counts. A later pass can treat counts_for_credit = false
-- as left out by changing that function, without a second scoring path.
--
-- Tiers stay new / regular / trusted / guide. The app calls regular "Local".
--   regular at 24, trusted at 80, guide at 200.
-- A removal in the last 90 days blocks a promotion. A hand-set tier is pinned
-- and the nightly job leaves it. Trusted and Guide need two more reports
-- before a drop is held for review.
--
-- The shared copy of these numbers is world.xhere.shared.reputation.Reputation.

-- ---------------------------------------------------------------------------
-- Rules, the ledger, and the spoof hook.
-- ---------------------------------------------------------------------------
alter table public.profiles
  add column tier_pin boolean not null default false;

comment on column public.profiles.tier_pin is
  'When true, reputation will not change this tier. Set when a person or a seed sets the tier. Automatic changes do not pin.';

comment on column public.profiles.points is
  'Reputation. Maintained from reputation_ledger. Clients cannot write it.';

comment on column public.confirmations.distance_m is
  'Distance at answer time. The caller''s coordinates are not stored.';

alter table public.confirmations
  add column spoof_flag boolean;

alter table public.drop_views
  add column spoof_flag boolean;

alter table public.walk_progress
  add column spoof_flag boolean;

comment on column public.confirmations.spoof_flag is
  'M12 hook. Null counts toward reputation. True is a suspicious location action and is left out. False was reviewed and still counts. Clients cannot set this.';

comment on column public.drop_views.spoof_flag is
  'M12 hook. Null counts toward reputation. True is left out. Clients cannot set this.';

comment on column public.walk_progress.spoof_flag is
  'M12 hook. Null counts a finished walk. True leaves that completion out of reputation. Clients cannot set this.';

create table public.reputation_rules (
  id integer primary key default 1 check (id = 1),
  confirm_first integer not null default 8 check (confirm_first >= 0),
  confirm_second integer not null default 3 check (confirm_second >= 0),
  confirm_third integer not null default 1 check (confirm_third >= 0),
  view_first integer not null default 2 check (view_first >= 0),
  view_next integer not null default 1 check (view_next >= 0),
  view_cap integer not null default 3 check (view_cap >= 1),
  deny_first integer not null default 6 check (deny_first >= 0),
  deny_second integer not null default 2 check (deny_second >= 0),
  moderation_penalty integer not null default 15 check (moderation_penalty >= 0),
  walk_points integer not null default 12 check (walk_points >= 0),
  quest_points integer not null default 10 check (quest_points >= 0),
  regular_at integer not null default 24 check (regular_at > 0),
  trusted_at integer not null default 80 check (trusted_at > regular_at),
  guide_at integer not null default 200 check (guide_at > trusted_at),
  trusted_extra_reports integer not null default 2 check (trusted_extra_reports >= 0),
  bonus_guide_m integer not null default 80 check (bonus_guide_m >= 0),
  bonus_trusted_m integer not null default 40 check (bonus_trusted_m >= 0),
  bonus_regular_m integer not null default 15 check (bonus_regular_m >= 0)
);

comment on table public.reputation_rules is
  'The one public copy of the reputation numbers. The nightly job and the level-up sheet both read this row.';

insert into public.reputation_rules (id) values (1);

create table public.reputation_ledger (
  id bigint generated always as identity primary key,
  user_id uuid not null references public.profiles (id) on delete cascade,
  actor_id uuid references public.profiles (id) on delete set null,
  drop_id uuid references public.drops (id) on delete cascade,
  kind text not null check (kind in ('confirm', 'view', 'deny', 'moderation', 'walk', 'quest')),
  source_key text not null unique check (char_length(source_key) between 1 and 200),
  points integer not null,
  -- Null counts. True is the M12 exclusion, kept on the row so clearing the
  -- flag can put the points back without recomputing them from scratch.
  -- Named left_out so ON CONFLICT can still use the excluded pseudo-row.
  left_out boolean,
  created_at timestamptz not null default now()
);

comment on table public.reputation_ledger is
  'One scored signal. profiles.points is the sum of rows whose left_out flag is not true. Clients cannot read it.';

create index reputation_ledger_user_idx on public.reputation_ledger (user_id);
create index reputation_ledger_drop_idx on public.reputation_ledger (drop_id);

create table public.quest_completions (
  user_id uuid not null references public.profiles (id) on delete cascade,
  quest_id uuid not null references public.quests (id) on delete cascade,
  completed_at timestamptz not null default now(),
  spoof_flag boolean,
  primary key (user_id, quest_id)
);

comment on table public.quest_completions is
  'A quest whose steps are all met. Written by the server. spoof_flag true leaves the points out (M12).';

comment on column public.quest_completions.spoof_flag is
  'M12 hook. Null counts. True leaves this quest out of reputation. Clients cannot set this.';

create index quest_completions_quest_idx on public.quest_completions (quest_id);

-- ---------------------------------------------------------------------------
-- RLS. The rules are public. The ledger is not. A completion is yours.
-- ---------------------------------------------------------------------------
alter table public.reputation_rules enable row level security;
alter table public.reputation_ledger enable row level security;
alter table public.quest_completions enable row level security;

revoke all on table public.reputation_rules from anon, authenticated;
revoke all on table public.reputation_ledger from anon, authenticated;
revoke all on table public.quest_completions from anon, authenticated;

grant select on public.reputation_rules to anon, authenticated;
grant select on public.quest_completions to authenticated;

create policy reputation_rules_select on public.reputation_rules
  for select to anon, authenticated
  using (true);

create policy quest_completions_select_own on public.quest_completions
  for select to authenticated
  using (user_id = (select auth.uid()));

-- Hand-set tiers that already exist stay put. New accounts are not pinned.
update public.profiles set tier_pin = true where tier <> 'new';

-- ---------------------------------------------------------------------------
-- Badges on the shelf the founding migration left for M11.
-- ---------------------------------------------------------------------------
insert into public.badges (slug, name, description, icon, scope, rule, sort)
values
  (
    'first_drop',
    'First drop',
    'You put something on the map.',
    '📍',
    'global',
    '{"kind":"first_drop","min":1}'::jsonb,
    20
  ),
  (
    'confirmed_10',
    'Ten confirmed',
    'Ten of your drops were confirmed.',
    '✅',
    'global',
    '{"kind":"confirmed_drops","min":10}'::jsonb,
    30
  ),
  (
    'walk_done',
    'Walked it',
    'You finished a walk.',
    '🚶',
    'global',
    '{"kind":"walk_completed","min":1}'::jsonb,
    40
  ),
  (
    'quest_done',
    'Quest done',
    'You finished a quest.',
    '🎯',
    'global',
    '{"kind":"quest_completed","min":1}'::jsonb,
    50
  ),
  (
    'seen_around',
    'Seen around',
    'People opened your drops 10 times.',
    '👀',
    'global',
    '{"kind":"unique_views","min":10}'::jsonb,
    60
  );

-- ---------------------------------------------------------------------------
-- Helpers. private is not exposed. search_path is empty.
-- ---------------------------------------------------------------------------
create function private.tier_for_points(p_points integer)
returns public.user_tier
language sql
stable
security definer
set search_path = ''
as $$
  select case
    when p_points >= r.guide_at then 'guide'::public.user_tier
    when p_points >= r.trusted_at then 'trusted'::public.user_tier
    when p_points >= r.regular_at then 'regular'::public.user_tier
    else 'new'::public.user_tier
  end
  from public.reputation_rules r
  where r.id = 1;
$$;

create function private.tier_rank_bonus_m(p_tier public.user_tier)
returns integer
language sql
stable
security definer
set search_path = ''
as $$
  select case p_tier
    when 'guide' then r.bonus_guide_m
    when 'trusted' then r.bonus_trusted_m
    when 'regular' then r.bonus_regular_m
    else 0
  end
  from public.reputation_rules r
  where r.id = 1;
$$;

create function private.confirm_points_for_index(p_index integer)
returns integer
language sql
stable
security definer
set search_path = ''
as $$
  select case p_index
    when 0 then r.confirm_first
    when 1 then r.confirm_second
    when 2 then r.confirm_third
    else 0
  end
  from public.reputation_rules r
  where r.id = 1;
$$;

create function private.view_points_for_index(p_index integer)
returns integer
language sql
stable
security definer
set search_path = ''
as $$
  select case
    when p_index <= 0 then r.view_first
    when p_index < r.view_cap then r.view_next
    else 0
  end
  from public.reputation_rules r
  where r.id = 1;
$$;

create function private.deny_points_for_index(p_index integer)
returns integer
language sql
stable
security definer
set search_path = ''
as $$
  select case p_index
    when 0 then r.deny_first
    when 1 then r.deny_second
    else 0
  end
  from public.reputation_rules r
  where r.id = 1;
$$;

-- Keeps profiles.points equal to the ledger rows that still count.
create function private.reputation_ledger_points()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_old integer := 0;
  v_new integer := 0;
begin
  if tg_op = 'UPDATE' and old.user_id is distinct from new.user_id then
    if old.left_out is not true then
      update public.profiles set points = points - old.points where id = old.user_id;
    end if;
    if new.left_out is not true then
      update public.profiles set points = points + new.points where id = new.user_id;
    end if;
    return new;
  end if;

  if tg_op in ('UPDATE', 'DELETE') and old.left_out is not true then
    v_old := old.points;
  end if;
  if tg_op in ('INSERT', 'UPDATE') and new.left_out is not true then
    v_new := new.points;
  end if;

  if v_new is distinct from v_old then
    update public.profiles
    set points = points + (v_new - v_old)
    where id = case when tg_op = 'DELETE' then old.user_id else new.user_id end;
  end if;

  if tg_op = 'DELETE' then
    return old;
  end if;
  return new;
end;
$$;

create trigger reputation_ledger_points
  after insert or update or delete on public.reputation_ledger
  for each row execute function private.reputation_ledger_points();

-- A tier set by hand stays. The reputation job sets xhere.tier_automatic
-- so its own update does not pin.
create function private.profiles_pin_tier()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if new.tier is distinct from old.tier
     and current_setting('xhere.tier_automatic', true) is distinct from 'on' then
    new.tier_pin := true;
  end if;
  return new;
end;
$$;

create trigger profiles_pin_tier
  before update of tier on public.profiles
  for each row execute function private.profiles_pin_tier();

create function private.has_reputation_strike(p_user uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1 from public.drops d
    where d.author_id = p_user
      and d.status = 'removed'
      and d.updated_at > now() - interval '90 days'
  );
$$;

create function private.apply_tier(p_user uuid, p_allow_demote boolean)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_points integer;
  v_tier public.user_tier;
  v_pin boolean;
  v_target public.user_tier;
begin
  select p.points, p.tier, p.tier_pin into v_points, v_tier, v_pin
  from public.profiles p
  where p.id = p_user and p.deleted_at is null;
  if not found or v_pin or not private.earns_reputation(p_user) then
    return;
  end if;

  v_target := private.tier_for_points(v_points);
  if private.has_reputation_strike(p_user) and v_target > v_tier then
    return;
  end if;
  if v_target = v_tier then
    return;
  end if;
  if v_target < v_tier and not p_allow_demote then
    return;
  end if;

  perform set_config('xhere.tier_automatic', 'on', true);
  perform set_config('xhere.audit_reason', 'reputation tier', true);
  update public.profiles set tier = v_target where id = p_user;
  perform set_config('xhere.tier_automatic', '', true);
  perform set_config('xhere.audit_reason', '', true);
end;
$$;

-- One predicate for "leave this signal out." Null and false still count.
-- M12 can extend this function when a row is marked counts_for_credit = false.
create function private.reputation_signal_left_out(p_spoof boolean)
returns boolean
language sql
immutable
security definer
set search_path = ''
as $$
  select p_spoof is true;
$$;

-- Demo accounts are not real contributors. Same gate as founding.
create function private.earns_reputation(p_user uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public.profiles p
    where p.id = p_user
      and p.deleted_at is null
      and not p.is_demo
  );
$$;

-- True when this direction holds the earliest still-true between the pair.
-- A spoofed or removed confirm does not count, so it cannot win the ring.
create function private.confirm_direction_scores(p_author uuid, p_actor uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  with edges as (
    select c.created_at, c.drop_id, d.author_id
    from public.confirmations c
    join public.drops d on d.id = c.drop_id
    where c.verdict = 'still_true'
      and not private.reputation_signal_left_out(c.spoof_flag)
      and d.source = 'user'
      and d.status <> 'removed'
      and (
        (d.author_id = p_author and c.user_id = p_actor)
        or (d.author_id = p_actor and c.user_id = p_author)
      )
  )
  select exists (
    select 1
    from edges e
    where e.author_id = p_author
      and (e.created_at, e.drop_id) = (
        select e2.created_at, e2.drop_id
        from edges e2
        order by e2.created_at, e2.drop_id
        limit 1
      )
  );
$$;

create function private.resync_confirm_direction(p_author uuid, p_actor uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_row record;
  v_prior integer;
  v_points integer;
  v_scores boolean;
  v_key text;
  v_excluded boolean;
begin
  if p_author is null or p_actor is null or p_author = p_actor then
    return;
  end if;
  if not private.earns_reputation(p_author) or not private.earns_reputation(p_actor) then
    delete from public.reputation_ledger l
    using public.drops d
    where l.drop_id = d.id
      and l.kind in ('confirm', 'deny')
      and d.author_id = p_author
      and l.actor_id = p_actor;
    return;
  end if;
  v_scores := private.confirm_direction_scores(p_author, p_actor);

  for v_row in
    select c.drop_id, c.user_id, c.verdict, c.spoof_flag, c.created_at, d.status, d.source
    from public.confirmations c
    join public.drops d on d.id = c.drop_id
    where d.author_id = p_author and c.user_id = p_actor
  loop
    v_key := 'confirm:' || v_row.drop_id::text || ':' || v_row.user_id::text;
    if v_row.source <> 'user' or v_row.status = 'removed' then
      delete from public.reputation_ledger where source_key = v_key;
      continue;
    end if;

    v_excluded := private.reputation_signal_left_out(v_row.spoof_flag);
    if v_row.verdict = 'still_true' then
      if not v_scores then
        v_points := 0;
      else
        select count(*)::integer into v_prior
        from public.confirmations c
        join public.drops d on d.id = c.drop_id
        where d.author_id = p_author
          and c.user_id = p_actor
          and d.source = 'user'
          and d.status <> 'removed'
          and c.verdict = 'still_true'
          and not private.reputation_signal_left_out(c.spoof_flag)
          and (c.created_at, c.drop_id) < (v_row.created_at, v_row.drop_id);
        v_points := private.confirm_points_for_index(v_prior);
      end if;
    else
      select count(*)::integer into v_prior
      from public.confirmations c
      join public.drops d on d.id = c.drop_id
      where d.author_id = p_author
        and c.user_id = p_actor
        and d.source = 'user'
        and d.status <> 'removed'
        and c.verdict = 'not_true'
        and not private.reputation_signal_left_out(c.spoof_flag)
        and (c.created_at, c.drop_id) < (v_row.created_at, v_row.drop_id);
      v_points := -private.deny_points_for_index(v_prior);
    end if;

    if v_points = 0 and not v_excluded then
      delete from public.reputation_ledger where source_key = v_key;
    else
      insert into public.reputation_ledger as l (
        user_id, actor_id, drop_id, kind, source_key, points, left_out
      ) values (
        p_author,
        p_actor,
        v_row.drop_id,
        case when v_row.verdict = 'still_true' then 'confirm' else 'deny' end,
        v_key,
        v_points,
        case when v_excluded then true else null end
      )
      on conflict (source_key) do update
        set points = excluded.points,
            kind = excluded.kind,
            left_out = excluded.left_out,
            user_id = excluded.user_id,
            actor_id = excluded.actor_id,
            drop_id = excluded.drop_id
        where l.points is distinct from excluded.points
           or l.kind is distinct from excluded.kind
           or l.left_out is distinct from excluded.left_out;
    end if;
  end loop;
end;
$$;

create function private.sync_view_reputation(p_drop_id uuid, p_viewer uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_author uuid;
  v_source public.drop_source;
  v_status public.drop_status;
  v_spoof boolean;
  v_seen boolean;
  v_prior integer;
  v_points integer;
  v_key text;
begin
  v_key := 'view:' || p_drop_id::text || ':' || p_viewer::text;

  select d.author_id, d.source, d.status
    into v_author, v_source, v_status
  from public.drops d
  where d.id = p_drop_id;

  select v.spoof_flag into v_spoof
  from public.drop_views v
  where v.drop_id = p_drop_id and v.viewer_id = p_viewer;
  v_seen := found;

  if not v_seen or v_author is null or v_author = p_viewer
     or v_source <> 'user' or v_status = 'removed'
     or not private.earns_reputation(v_author)
     or not private.earns_reputation(p_viewer) then
    delete from public.reputation_ledger where source_key = v_key;
    return;
  end if;

  select count(*)::integer into v_prior
  from public.drop_views v
  join public.drops d on d.id = v.drop_id
  where v.viewer_id = p_viewer
    and d.author_id = v_author
    and d.source = 'user'
    and d.status <> 'removed'
    and not private.reputation_signal_left_out(v.spoof_flag)
    and v.drop_id <> p_drop_id;

  v_points := private.view_points_for_index(v_prior);
  if v_points = 0 and not private.reputation_signal_left_out(v_spoof) then
    delete from public.reputation_ledger where source_key = v_key;
    return;
  end if;

  insert into public.reputation_ledger as l (
    user_id, actor_id, drop_id, kind, source_key, points, left_out
  ) values (
    v_author, p_viewer, p_drop_id, 'view', v_key, v_points,
    case when private.reputation_signal_left_out(v_spoof) then true else null end
  )
  on conflict (source_key) do update
    set points = excluded.points,
        left_out = excluded.left_out,
        user_id = excluded.user_id
    where l.points is distinct from excluded.points
       or l.left_out is distinct from excluded.left_out;
end;
$$;

create function private.sync_reputation_badges(p_user uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_drops integer;
  v_confirmed integer;
  v_walks integer;
  v_quests integer;
  v_views integer;
  v_badge record;
  v_ok boolean;
  v_min integer;
begin
  if p_user is null then
    return;
  end if;
  if not private.earns_reputation(p_user) then
    delete from public.user_badges ub
    using public.badges b
    where ub.badge_id = b.id
      and ub.user_id = p_user
      and ub.region_id is null
      and b.rule ->> 'kind' in (
        'first_drop', 'confirmed_drops', 'walk_completed', 'quest_completed', 'unique_views'
      );
    return;
  end if;

  select count(*)::integer into v_drops
  from public.drops d
  where d.author_id = p_user and d.source = 'user' and d.status <> 'removed';

  select count(*)::integer into v_confirmed
  from public.drops d
  where d.author_id = p_user and d.source = 'user' and d.status = 'confirmed';

  select count(*)::integer into v_walks
  from public.walk_progress w
  where w.user_id = p_user
    and w.completed_at is not null
    and not private.reputation_signal_left_out(w.spoof_flag);

  select count(*)::integer into v_quests
  from public.quest_completions q
  where q.user_id = p_user and not private.reputation_signal_left_out(q.spoof_flag);

  select count(*)::integer into v_views
  from public.drop_views v
  join public.drops d on d.id = v.drop_id
  where d.author_id = p_user
    and d.source = 'user'
    and d.status <> 'removed'
    and not private.reputation_signal_left_out(v.spoof_flag)
    and v.viewer_id <> p_user
    and private.earns_reputation(v.viewer_id);

  for v_badge in
    select b.id, b.rule
    from public.badges b
    where b.active
      and b.scope = 'global'
      and b.rule ->> 'kind' in (
        'first_drop', 'confirmed_drops', 'walk_completed', 'quest_completed', 'unique_views'
      )
  loop
    v_min := coalesce((v_badge.rule ->> 'min')::integer, 1);
    v_ok := case v_badge.rule ->> 'kind'
      when 'first_drop' then v_drops >= v_min
      when 'confirmed_drops' then v_confirmed >= v_min
      when 'walk_completed' then v_walks >= v_min
      when 'quest_completed' then v_quests >= v_min
      when 'unique_views' then v_views >= v_min
      else false
    end;

    if v_ok then
      insert into public.user_badges (user_id, badge_id, region_id, meta)
      values (p_user, v_badge.id, null, '{}'::jsonb)
      on conflict on constraint user_badges_user_badge_region_key do nothing;
    else
      delete from public.user_badges ub
      where ub.user_id = p_user and ub.badge_id = v_badge.id and ub.region_id is null;
    end if;
  end loop;
end;
$$;

create function private.award_finished_quests(p_user uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_quest record;
  v_points integer;
  v_key text;
begin
  if p_user is null or not private.earns_reputation(p_user) then
    return;
  end if;
  select r.quest_points into v_points from public.reputation_rules r where r.id = 1;

  for v_quest in
    select q.id
    from public.quests q
    where q.active
      and not exists (
        select 1 from public.quest_steps s
        where s.quest_id = q.id
          and private.quest_step_progress(p_user, s.kind) < s.target_count
      )
  loop
    insert into public.quest_completions (user_id, quest_id)
    values (p_user, v_quest.id)
    on conflict (user_id, quest_id) do nothing;

    v_key := 'quest:' || p_user::text || ':' || v_quest.id::text;
    insert into public.reputation_ledger (user_id, kind, source_key, points, left_out)
    select p_user, 'quest', v_key, v_points,
           case when private.reputation_signal_left_out(c.spoof_flag) then true else null end
    from public.quest_completions c
    where c.user_id = p_user and c.quest_id = v_quest.id
    on conflict (source_key) do nothing;
  end loop;

  perform private.sync_reputation_badges(p_user);
end;
$$;

create function private.sync_walk_reputation()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_key text := 'walk:' || new.user_id::text || ':' || new.walk_id::text;
  v_points integer;
begin
  if tg_op = 'DELETE' then
    delete from public.reputation_ledger
    where source_key = 'walk:' || old.user_id::text || ':' || old.walk_id::text;
    perform private.apply_tier(old.user_id, true);
    return null;
  end if;

  if new.completed_at is null or not private.earns_reputation(new.user_id) then
    delete from public.reputation_ledger where source_key = v_key;
  else
    select r.walk_points into v_points from public.reputation_rules r where r.id = 1;
    insert into public.reputation_ledger as l (user_id, kind, source_key, points, left_out)
    values (
      new.user_id, 'walk', v_key, v_points,
      case when private.reputation_signal_left_out(new.spoof_flag) then true else null end
    )
    on conflict (source_key) do update
      set points = excluded.points,
          left_out = excluded.left_out
      where l.points is distinct from excluded.points
         or l.left_out is distinct from excluded.left_out;
  end if;

  perform private.apply_tier(new.user_id, true);
  perform private.sync_reputation_badges(new.user_id);
  return null;
end;
$$;

create function private.sync_drop_reputation()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_actor uuid;
  v_viewer uuid;
  v_penalty integer;
begin
  if tg_op = 'INSERT' then
    if new.source = 'user' and new.author_id is not null and new.status <> 'removed'
       and private.earns_reputation(new.author_id) then
      perform private.award_finished_quests(new.author_id);
      perform private.apply_tier(new.author_id, true);
      perform private.sync_reputation_badges(new.author_id);
    end if;
    return null;
  end if;

  if new.author_id is null or new.source <> 'user' then
    delete from public.reputation_ledger where drop_id = new.id;
    if old.author_id is not null then
      perform private.apply_tier(old.author_id, true);
      perform private.sync_reputation_badges(old.author_id);
    end if;
    return null;
  end if;

  if not private.earns_reputation(new.author_id) then
    delete from public.reputation_ledger where drop_id = new.id;
    perform private.sync_reputation_badges(new.author_id);
    return null;
  end if;

  if new.status = 'removed' and old.status is distinct from 'removed' then
    delete from public.reputation_ledger
    where drop_id = new.id and kind in ('confirm', 'deny', 'view');
    select r.moderation_penalty into v_penalty from public.reputation_rules r where r.id = 1;
    insert into public.reputation_ledger (user_id, drop_id, kind, source_key, points)
    values (new.author_id, new.id, 'moderation', 'moderation:' || new.id::text, -v_penalty)
    on conflict (source_key) do update
      set points = excluded.points, left_out = null;
    perform private.apply_tier(new.author_id, true);
    perform private.sync_reputation_badges(new.author_id);
    return null;
  end if;

  if old.status = 'removed' and new.status is distinct from 'removed' then
    delete from public.reputation_ledger where source_key = 'moderation:' || new.id::text;
    for v_actor in
      select c.user_id from public.confirmations c where c.drop_id = new.id
    loop
      perform private.resync_confirm_direction(new.author_id, v_actor);
      perform private.resync_confirm_direction(v_actor, new.author_id);
    end loop;
    for v_viewer in
      select v.viewer_id from public.drop_views v where v.drop_id = new.id
    loop
      perform private.sync_view_reputation(new.id, v_viewer);
    end loop;
    perform private.apply_tier(new.author_id, true);
    perform private.sync_reputation_badges(new.author_id);
    return null;
  end if;

  if new.status is distinct from old.status then
    perform private.sync_reputation_badges(new.author_id);
  end if;
  return null;
end;
$$;

create function private.sync_confirmation_reputation()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_author uuid;
  v_actor uuid;
  v_drop uuid;
begin
  if tg_op = 'DELETE' then
    v_actor := old.user_id;
    v_drop := old.drop_id;
    delete from public.reputation_ledger
    where source_key = 'confirm:' || old.drop_id::text || ':' || old.user_id::text;
  else
    v_actor := new.user_id;
    v_drop := new.drop_id;
  end if;

  select d.author_id into v_author from public.drops d where d.id = v_drop;
  if v_author is not null and v_author is distinct from v_actor then
    perform private.resync_confirm_direction(v_author, v_actor);
    perform private.resync_confirm_direction(v_actor, v_author);
    perform private.apply_tier(v_author, true);
    perform private.sync_reputation_badges(v_author);
    if tg_op <> 'DELETE' then
      perform private.award_finished_quests(v_actor);
    end if;
  end if;
  return null;
end;
$$;

create function private.sync_view_flag()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_author uuid;
begin
  perform private.sync_view_reputation(new.drop_id, new.viewer_id);
  select d.author_id into v_author from public.drops d where d.id = new.drop_id;
  if v_author is not null then
    perform private.apply_tier(v_author, true);
    perform private.sync_reputation_badges(v_author);
  end if;
  return null;
end;
$$;

create function private.sync_quest_flag()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_key text := 'quest:' || new.user_id::text || ':' || new.quest_id::text;
begin
  update public.reputation_ledger
  set left_out = case when private.reputation_signal_left_out(new.spoof_flag) then true else null end
  where source_key = v_key;
  perform private.apply_tier(new.user_id, true);
  perform private.sync_reputation_badges(new.user_id);
  return null;
end;
$$;

create function private.sync_place_visit_quest()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform private.award_finished_quests(new.user_id);
  perform private.apply_tier(new.user_id, true);
  return null;
end;
$$;

create trigger drops_reputation
  after insert or update of status, source on public.drops
  for each row execute function private.sync_drop_reputation();

create trigger confirmations_reputation
  after insert or update or delete on public.confirmations
  for each row execute function private.sync_confirmation_reputation();

create trigger drop_views_spoof
  after update of spoof_flag on public.drop_views
  for each row execute function private.sync_view_flag();

create trigger walk_progress_reputation
  after insert or update of completed_at, spoof_flag or delete on public.walk_progress
  for each row execute function private.sync_walk_reputation();

create trigger quest_completions_spoof
  after update of spoof_flag on public.quest_completions
  for each row execute function private.sync_quest_flag();

create trigger place_visits_quest
  after insert on public.place_visits
  for each row execute function private.sync_place_visit_quest();

-- Put one account's signals back after it stops being a demo.
create function private.rebuild_user_reputation(p_user uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_author uuid;
  v_actor uuid;
  v_drop uuid;
  v_viewer uuid;
  v_penalty integer;
begin
  if not private.earns_reputation(p_user) then
    return;
  end if;

  for v_author, v_actor in
    select d.author_id, c.user_id
    from public.confirmations c
    join public.drops d on d.id = c.drop_id
    where c.user_id = p_user or d.author_id = p_user
  loop
    perform private.resync_confirm_direction(v_author, v_actor);
    if v_author is distinct from v_actor then
      perform private.resync_confirm_direction(v_actor, v_author);
    end if;
  end loop;

  for v_drop, v_viewer in
    select v.drop_id, v.viewer_id
    from public.drop_views v
    join public.drops d on d.id = v.drop_id
    where v.viewer_id = p_user or d.author_id = p_user
  loop
    perform private.sync_view_reputation(v_drop, v_viewer);
  end loop;

  update public.walk_progress w
  set completed_at = w.completed_at
  where w.user_id = p_user and w.completed_at is not null;

  perform private.award_finished_quests(p_user);

  select r.moderation_penalty into v_penalty from public.reputation_rules r where r.id = 1;
  insert into public.reputation_ledger (user_id, drop_id, kind, source_key, points)
  select p_user, d.id, 'moderation', 'moderation:' || d.id::text, -v_penalty
  from public.drops d
  where d.author_id = p_user and d.source = 'user' and d.status = 'removed'
  on conflict (source_key) do nothing;

  perform private.sync_reputation_badges(p_user);
  perform private.apply_tier(p_user, true);

  for v_author in
    select distinct d.author_id
    from public.confirmations c
    join public.drops d on d.id = c.drop_id
    where c.user_id = p_user and d.author_id is distinct from p_user
  loop
    perform private.apply_tier(v_author, true);
    perform private.sync_reputation_badges(v_author);
  end loop;
end;
$$;

create function private.sync_reputation_when_demo_changes()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_other uuid;
  v_others uuid[];
begin
  if new.is_demo is not distinct from old.is_demo then
    return null;
  end if;

  if new.is_demo then
    select coalesce(array_agg(distinct s.id), '{}'::uuid[]) into v_others
    from (
      select l.user_id as id
      from public.reputation_ledger l
      where l.actor_id = new.id and l.user_id <> new.id
      union
      select l.actor_id
      from public.reputation_ledger l
      where l.user_id = new.id and l.actor_id is not null and l.actor_id <> new.id
    ) s;

    delete from public.reputation_ledger l
    where l.user_id = new.id or l.actor_id = new.id;

    perform private.sync_reputation_badges(new.id);

    foreach v_other in array v_others loop
      perform private.apply_tier(v_other, true);
      perform private.sync_reputation_badges(v_other);
    end loop;
    return null;
  end if;

  perform private.rebuild_user_reputation(new.id);
  return null;
end;
$$;

create trigger profiles_sync_reputation_when_demo_changes
  after update of is_demo on public.profiles
  for each row execute function private.sync_reputation_when_demo_changes();

-- First open still increments view_count. It also scores, unless this is
-- the author, a repeat, or a public record (no author).
create or replace function private.record_drop_view(p_drop_id uuid, p_author uuid)
returns bigint
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_count bigint;
  v_inserted bigint;
begin
  if v_uid is null or p_author is not distinct from v_uid then
    select d.view_count into v_count from public.drops d where d.id = p_drop_id;
    return coalesce(v_count, 0);
  end if;

  insert into public.drop_views (drop_id, viewer_id)
  values (p_drop_id, v_uid)
  on conflict (drop_id, viewer_id) do nothing;
  get diagnostics v_inserted = row_count;

  if v_inserted = 0 then
    select d.view_count into v_count from public.drops d where d.id = p_drop_id;
    return coalesce(v_count, 0);
  end if;

  update public.drops d
  set view_count = d.view_count + 1
  where d.id = p_drop_id
  returning view_count into v_count;

  perform private.note_drop_helped(p_author, p_drop_id, 'view');
  perform private.sync_view_reputation(p_drop_id, v_uid);
  if p_author is not null then
    perform private.apply_tier(p_author, true);
    perform private.sync_reputation_badges(p_author);
  end if;
  return coalesce(v_count, 0);
end;
$$;

-- ---------------------------------------------------------------------------
-- Nightly tier pass. Promotions and demotions follow the score. A pinned
-- tier stays. A removal in the last 90 days blocks a promotion only.
-- ---------------------------------------------------------------------------
create or replace function private.recompute_tiers()
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_count integer;
  v_user uuid;
begin
  perform set_config('xhere.tier_automatic', 'on', true);
  perform set_config('xhere.audit_reason', 'reputation tier', true);

  with targets as (
    select p.id,
           case
             when p.tier_pin then p.tier
             when private.has_reputation_strike(p.id) and private.tier_for_points(p.points) > p.tier then p.tier
             else private.tier_for_points(p.points)
           end as target
    from public.profiles p
    where p.deleted_at is null
      and not p.is_demo
  ),
  updated as (
    update public.profiles p
    set tier = t.target
    from targets t
    where p.id = t.id and p.tier is distinct from t.target
    returning p.id
  )
  select count(*)::integer into v_count from updated;

  for v_user in select p.id from public.profiles p where p.deleted_at is null loop
    perform private.sync_reputation_badges(v_user);
  end loop;

  perform set_config('xhere.tier_automatic', '', true);
  perform set_config('xhere.audit_reason', '', true);
  return v_count;
end;
$$;

-- ---------------------------------------------------------------------------
-- What the level-up sheet reads. Labels match the app: regular is Local.
-- ---------------------------------------------------------------------------
create function public.my_reputation()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_profile public.profiles;
  r public.reputation_rules;
  v_next public.user_tier;
  v_next_at integer;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  select * into v_profile from public.profiles p where p.id = v_uid and p.deleted_at is null;
  if not found then
    raise exception 'account_unavailable' using errcode = '42501';
  end if;
  select * into r from public.reputation_rules where id = 1;

  v_next := case v_profile.tier
    when 'new' then 'regular'::public.user_tier
    when 'regular' then 'trusted'::public.user_tier
    when 'trusted' then 'guide'::public.user_tier
    else null
  end;
  v_next_at := case v_next
    when 'regular' then r.regular_at
    when 'trusted' then r.trusted_at
    when 'guide' then r.guide_at
    else null
  end;

  return jsonb_build_object(
    'points', v_profile.points,
    'tier', v_profile.tier,
    'tier_label', case v_profile.tier
      when 'regular' then 'Local'
      when 'trusted' then 'Trusted'
      when 'guide' then 'Guide'
      else 'New'
    end,
    'next_tier', v_next,
    'next_label', case v_next
      when 'regular' then 'Local'
      when 'trusted' then 'Trusted'
      when 'guide' then 'Guide'
      else null
    end,
    'next_at', v_next_at,
    'ladder', jsonb_build_array(
      jsonb_build_object('tier', 'new', 'label', 'New', 'at', 0),
      jsonb_build_object('tier', 'regular', 'label', 'Local', 'at', r.regular_at),
      jsonb_build_object('tier', 'trusted', 'label', 'Trusted', 'at', r.trusted_at),
      jsonb_build_object('tier', 'guide', 'label', 'Guide', 'at', r.guide_at)
    ),
    'confirm_first', r.confirm_first,
    'confirm_second', r.confirm_second,
    'confirm_third', r.confirm_third,
    'view_first', r.view_first,
    'view_next', r.view_next,
    'view_cap', r.view_cap,
    'deny_first', r.deny_first,
    'deny_second', r.deny_second,
    'moderation_penalty', r.moderation_penalty,
    'walk_points', r.walk_points,
    'quest_points', r.quest_points,
    'trusted_extra_reports', r.trusted_extra_reports
  );
end;
$$;

-- ---------------------------------------------------------------------------
-- Author tier on the card, and trust-weighted order for the map list and
-- the Here Card. Public records and anonymous drops get no bonus.
-- ---------------------------------------------------------------------------
alter type public.drop_card add attribute author_tier public.user_tier;

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
  v_card.is_mine := p_viewer is not null
    and p_drop.author_id is not null
    and p_drop.author_id = p_viewer;
  v_card.source := p_drop.source;
  v_card.author_badges := '[]'::jsonb;
  v_card.author_tier := null;

  if p_viewer is not null then
    select c.verdict into v_card.my_verdict
    from public.confirmations c
    where c.drop_id = p_drop.id and c.user_id = p_viewer;
  end if;

  if p_drop.author_id is not null
     and not (p_drop.display_anonymous and v_category.allow_anonymous_display) then
    v_card.author_handle := (select p.handle from public.profiles p where p.id = p_drop.author_id);
    v_card.author_badges := private.author_badges_json(p_drop.author_id, p_drop.geog);
    v_card.author_tier := (select p.tier from public.profiles p where p.id = p_drop.author_id);
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

create or replace function public.drops_nearby(
  p_lat double precision,
  p_lng double precision,
  p_radius_m integer default 1000,
  p_categories text[] default null,
  p_limit integer default 200
)
returns setof public.drop_card
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_point extensions.geography := private.make_point(p_lat, p_lng);
  v_uid uuid := auth.uid();
begin
  if p_radius_m is null or p_radius_m not between 1 and 50000 then
    raise exception 'invalid_radius' using errcode = '22023', detail = 'radius must be 1..50000 m';
  end if;

  return query
  with scored as (
    select d as drop_row,
           extensions.st_distance(d.display_geog, v_point) as dist,
           case
             when d.source <> 'user' or d.author_id is null then 0
             when d.display_anonymous and c.allow_anonymous_display then 0
             else private.tier_rank_bonus_m(p.tier)
           end as bonus,
           d.created_at as posted_at
    from public.drops d
    join public.categories c on c.id = d.category_id
    join public.category_groups g on g.id = c.group_id
    left join public.profiles p on p.id = d.author_id
    where extensions.st_dwithin(d.display_geog, v_point, p_radius_m)
      and (p_categories is null or c.slug = any (p_categories) or g.slug = any (p_categories))
      and private.can_view_drop(d, v_uid)
  ),
  hits as (
    select scored.drop_row, scored.dist, scored.bonus, scored.posted_at
    from scored
    order by scored.dist - scored.bonus, scored.posted_at desc
    limit least(greatest(coalesce(p_limit, 200), 1), 500)
  )
  select card.*
  from hits
  cross join lateral private.to_drop_card(hits.drop_row, v_uid, v_point) as card
  order by hits.dist - hits.bonus, card.created_at desc;
end;
$$;

create or replace function public.here_card(
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
    order by
      extensions.st_distance(d.display_geog, v_tap)
        - coalesce((
            select case
              when d.source <> 'user' or d.author_id is null then 0
              when d.display_anonymous and c.allow_anonymous_display then 0
              else private.tier_rank_bonus_m(p.tier)
            end
            from public.categories c
            left join public.profiles p on p.id = d.author_id
            where c.id = d.category_id
          ), 0),
      d.created_at desc
    limit v_limit
  loop
    v_card := private.to_drop_card(v_drop, v_uid, v_viewer);
    v_card.distance_m := round(extensions.st_distance(v_drop.display_geog, v_tap)::numeric, 1)::double precision;
    return next v_card;
  end loop;
end;
$$;

-- Trusted and Guide need more distinct reports before a drop is held.
create or replace function public.report(
  p_target_type public.report_target,
  p_target_id uuid,
  p_reason public.report_reason,
  p_details text default null
)
returns uuid
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_profile public.profiles;
  v_drop public.drops;
  v_report_id uuid;
  v_limit integer;
  v_recent integer;
  v_open_reporters integer;
  v_threshold integer;
  v_author_tier public.user_tier;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  select * into v_profile from public.profiles p where p.id = v_uid;
  if not found or v_profile.deleted_at is not null then
    raise exception 'account_unavailable' using errcode = '42501';
  end if;
  if p_reason is null or p_target_id is null or p_target_type is null then
    raise exception 'invalid_report' using errcode = '22023';
  end if;
  if char_length(p_details) > 1000 then
    raise exception 'invalid_report' using errcode = '22023', detail = 'details must be at most 1000 characters';
  end if;

  if p_target_type = 'drop' then
    select * into v_drop from public.drops d where d.id = p_target_id;
    if not found or not private.can_view_drop(v_drop, v_uid) then
      raise exception 'drop_not_found' using errcode = 'P0002';
    end if;
    if v_drop.author_id = v_uid then
      raise exception 'cannot_report_self' using errcode = '22023';
    end if;
  else
    if p_target_id = v_uid then
      raise exception 'cannot_report_self' using errcode = '22023';
    end if;
    if not exists (select 1 from public.profiles p where p.id = p_target_id and p.deleted_at is null) then
      raise exception 'user_not_found' using errcode = 'P0002';
    end if;
  end if;

  select r.id into v_report_id
  from public.reports r
  where r.reporter_id = v_uid and r.target_type = p_target_type and r.target_id = p_target_id and r.status = 'open';
  if found then
    return v_report_id;
  end if;

  select tl.reports_per_day into v_limit from public.tier_limits tl where tl.tier = v_profile.tier;
  select count(*) into v_recent
  from public.reports r
  where r.reporter_id = v_uid and r.created_at > now() - interval '1 day';
  if v_recent >= coalesce(v_limit, 0) then
    raise exception 'rate_limited' using errcode = 'PT429',
      detail = format('tier %s allows %s reports per day', v_profile.tier, coalesce(v_limit, 0));
  end if;

  insert into public.reports (target_type, target_id, reporter_id, reason, details)
  values (p_target_type, p_target_id, v_uid, p_reason, nullif(btrim(p_details), ''))
  returning id into v_report_id;

  if p_target_type = 'drop' and v_drop.status in ('active', 'confirmed') then
    select c.reports_to_hide into v_threshold from public.categories c where c.id = v_drop.category_id;
    if v_drop.author_id is not null then
      select p.tier into v_author_tier from public.profiles p where p.id = v_drop.author_id;
      if v_author_tier >= 'trusted'::public.user_tier then
        v_threshold := v_threshold + (
          select rules.trusted_extra_reports from public.reputation_rules rules where rules.id = 1
        );
      end if;
    end if;
    select count(distinct r.reporter_id) into v_open_reporters
    from public.reports r
    where r.target_type = 'drop' and r.target_id = v_drop.id and r.status = 'open';
    if v_open_reporters >= v_threshold then
      update public.drops set status = 'pending_review' where id = v_drop.id;
      insert into public.moderation_actions (actor_id, target_type, target_id, action, reason, metadata)
      values (null, 'drop', v_drop.id, 'auto_hide', 'report threshold reached',
              jsonb_build_object('open_reporters', v_open_reporters, 'threshold', v_threshold));
    end if;
  end if;

  return v_report_id;
end;
$$;

-- ---------------------------------------------------------------------------
-- Grants. New private helpers stay closed. my_reputation needs an account.
-- ---------------------------------------------------------------------------
revoke execute on all functions in schema private from public;

grant execute on function private.is_moderator() to anon, authenticated, service_role;
grant execute on function private.is_admin() to anon, authenticated, service_role;
grant execute on function private.is_drop_author(uuid) to authenticated, service_role;

revoke execute on function public.my_reputation() from public, anon, authenticated;
grant execute on function public.my_reputation() to authenticated;

revoke execute on function private.sync_reputation_when_demo_changes() from public, anon, authenticated;
revoke execute on function private.rebuild_user_reputation(uuid) from public, anon, authenticated;
grant execute on function private.sync_reputation_when_demo_changes() to service_role;
