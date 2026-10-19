-- Claimed places and time capsules.
--
-- A business or organization can claim an open-data pin or a place they
-- created. Verification v1 is free: stand within 50 m (location integrity)
-- and then one of email-domain match, a postcard stub, or an admin review.
-- Billing is not built. place_plans exists so a paid row can be added later.
-- Activation refuses any plan with paid = true.
--
-- A time capsule keeps its story in private.capsule_secrets until a date,
-- a visit within 30 m, or both. drops.body stays null while it is locked.
-- A trigger puts the body back to null if the author writes it early.
-- Stable reads never copy the secret. Only drop_detail, open_capsules_here,
-- and the cron job release it.
--
-- Not applied on hosted Supabase from this repo. Do not paste this file
-- into the dashboard. It does not change Auth.

-- ---------------------------------------------------------------------------
-- Plans. One free row. Paid rows stay inactive until a billing migration.
-- ---------------------------------------------------------------------------
create table public.place_plans (
  slug text primary key check (slug ~ '^[a-z][a-z0-9_]{1,32}$'),
  label text not null check (char_length(label) between 1 and 60),
  paid boolean not null default false,
  active boolean not null default true
);

insert into public.place_plans (slug, label, paid, active)
values ('free', 'Free', false, true);

comment on table public.place_plans is
  'Catalog only. Billing is not implemented. Activation refuses paid = true. A later migration can add an inactive paid row.';

-- ---------------------------------------------------------------------------
-- A claimable place. The anchor drop is an open-data pin or a user place.
-- geog is copied from that drop. Clients do not read this table.
-- ---------------------------------------------------------------------------
create table public.places (
  id uuid primary key default gen_random_uuid(),
  kind text not null check (kind in ('open_data', 'user')),
  anchor_drop_id uuid not null unique references public.drops (id) on delete cascade,
  name text not null check (char_length(btrim(name)) between 1 and 120),
  website_url text,
  website_domain text,
  phone text,
  geog extensions.geography(Point, 4326) not null,
  created_by uuid references public.profiles (id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index places_geog_idx on public.places using gist (geog);

comment on column public.places.phone is
  'Reserved for a later check. SMS is not implemented and is not sent.';
comment on column public.places.geog is
  'Copied from the anchor drop. Not returned to clients.';

create table public.place_teams (
  id uuid primary key default gen_random_uuid(),
  place_id uuid not null references public.places (id) on delete cascade,
  plan_slug text not null default 'free' references public.place_plans (slug),
  status text not null check (status in ('forming', 'active', 'dissolved')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create unique index place_teams_one_active on public.place_teams (place_id) where status = 'active';

comment on table public.place_teams is
  'One active owner team per place. plan_slug is free until billing exists.';

create table public.place_team_members (
  team_id uuid not null references public.place_teams (id) on delete cascade,
  user_id uuid not null references public.profiles (id) on delete cascade,
  role text not null check (role in ('owner', 'co_owner')),
  status text not null check (status in ('invited', 'pending', 'active', 'removed')),
  invited_by uuid references public.profiles (id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (team_id, user_id)
);

create unique index place_team_one_owner
  on public.place_team_members (team_id)
  where role = 'owner' and status = 'active';

create table public.place_claims (
  id uuid primary key default gen_random_uuid(),
  place_id uuid not null references public.places (id) on delete cascade,
  team_id uuid not null references public.place_teams (id) on delete cascade,
  claimant_id uuid not null references public.profiles (id) on delete cascade,
  status text not null check (status in ('pending_proof', 'pending_review', 'active', 'rejected', 'revoked')),
  onsite_distance_m double precision not null check (onsite_distance_m >= 0),
  onsite_at timestamptz not null default now(),
  proof_method text check (proof_method in ('postcard', 'email_domain', 'admin_review')),
  email_domain text,
  postcard_requested_at timestamptz,
  postcard_code_hash text,
  review_note text,
  reviewed_by uuid references public.profiles (id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create unique index place_claims_one_open
  on public.place_claims (place_id)
  where status in ('pending_proof', 'pending_review', 'active');

comment on column public.place_claims.postcard_code_hash is
  'Hash only. The code is never returned. No mailer is wired; see request_claim_postcard.';

create table public.place_official (
  place_id uuid primary key references public.places (id) on delete cascade,
  hours jsonb not null default '[]'::jsonb check (jsonb_typeof(hours) = 'array'),
  summary text check (summary is null or char_length(summary) between 1 and 500),
  updated_by uuid references public.profiles (id) on delete set null,
  updated_at timestamptz not null default now()
);

comment on table public.place_official is
  'Hours and a short official line. Shown only while a claim is active.';

create table public.claim_disputes (
  id uuid primary key default gen_random_uuid(),
  claim_id uuid not null references public.place_claims (id) on delete cascade,
  place_id uuid not null references public.places (id) on delete cascade,
  reporter_id uuid not null references public.profiles (id) on delete cascade,
  reason text not null check (char_length(btrim(reason)) between 1 and 80),
  details text check (details is null or char_length(details) between 1 and 500),
  status text not null default 'open' check (status in ('open', 'actioned', 'dismissed')),
  resolved_by uuid references public.profiles (id) on delete set null,
  resolved_at timestamptz,
  created_at timestamptz not null default now()
);

create unique index claim_disputes_one_open
  on public.claim_disputes (place_id, reporter_id)
  where status = 'open';

comment on table public.claim_disputes is
  'A dispute does not remove the Official badge. An admin revokes the claim.';

create table public.owner_replies (
  id uuid primary key default gen_random_uuid(),
  place_id uuid not null references public.places (id) on delete cascade,
  drop_id uuid not null references public.drops (id) on delete cascade,
  author_id uuid not null references public.profiles (id) on delete cascade,
  body text not null check (char_length(btrim(body)) between 1 and 500),
  created_at timestamptz not null default now()
);

create index owner_replies_drop_idx on public.owner_replies (drop_id, created_at);

-- ---------------------------------------------------------------------------
-- Locked story text. No policy, no grant. Security-definer functions only.
-- ---------------------------------------------------------------------------
create table private.capsule_secrets (
  drop_id uuid primary key references public.drops (id) on delete cascade,
  body text,
  media jsonb not null default '[]'::jsonb check (jsonb_typeof(media) = 'array'),
  created_at timestamptz not null default now()
);

comment on table private.capsule_secrets is
  'Story text and media for a locked time capsule. Copied onto the drop when it opens, then deleted.';

alter table private.capsule_secrets enable row level security;
revoke all on table private.capsule_secrets from public, anon, authenticated;

-- ---------------------------------------------------------------------------
-- Drop columns. place_id links an official announcement to its place.
-- capsule_* is public metadata. The body stays null until release.
-- ---------------------------------------------------------------------------
alter table public.drops
  add column place_id uuid references public.places (id) on delete set null,
  add column capsule_mode text check (capsule_mode in ('date', 'visit', 'both')),
  add column capsule_unlock_at timestamptz,
  add column capsule_opened_at timestamptz,
  add column capsule_opened_by uuid references public.profiles (id) on delete set null,
  add column oral_history boolean not null default false;

alter table public.drops
  add constraint drops_capsule_unlock_ck check (
    capsule_mode is null
    or (capsule_mode = 'visit' and capsule_unlock_at is null)
    or (capsule_mode in ('date', 'both') and capsule_unlock_at is not null)
  );

create index drops_place_idx on public.drops (place_id) where place_id is not null;
create index drops_capsule_due_idx on public.drops (capsule_unlock_at)
  where capsule_mode in ('date', 'both') and capsule_opened_at is null;

-- Authors can update body. This puts it back to null while the capsule is locked.
-- The release update sets opened_at and body in the same statement, so the body stays.
create function private.guard_locked_capsule()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.capsule_mode is not null and new.capsule_opened_at is null then
    new.body := null;
    new.attributes := '{}'::jsonb;
  end if;
  return new;
end;
$$;

create trigger drops_guard_locked_capsule
  before insert or update on public.drops
  for each row execute function private.guard_locked_capsule();

-- ---------------------------------------------------------------------------
-- Inbox: one more counter on the same daily row. No push.
-- ---------------------------------------------------------------------------
alter table public.impact_notices
  add column capsule_opens integer not null default 0 check (capsule_opens >= 0);

do $drop_check$
declare
  v_name text;
begin
  select con.conname into v_name
  from pg_constraint con
  join pg_class rel on rel.oid = con.conrelid
  join pg_namespace nsp on nsp.oid = rel.relnamespace
  where nsp.nspname = 'public'
    and rel.relname = 'impact_notices'
    and con.contype = 'c'
    and pg_get_constraintdef(con.oid) like '%view_events%confirm_events%'
    and pg_get_constraintdef(con.oid) not like '%capsule_opens%';
  if v_name is not null then
    execute format('alter table public.impact_notices drop constraint %I', v_name);
  end if;
end;
$drop_check$;

alter table public.impact_notices
  add constraint impact_notices_has_event
  check (view_events + confirm_events + capsule_opens > 0);

create or replace function private.note_drop_helped(
  p_author uuid,
  p_drop_id uuid,
  p_kind text
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  if p_author is null or p_kind not in ('view', 'confirm', 'open') then
    return;
  end if;

  insert into public.impact_notices as n (
    recipient_id, drop_id, notice_day, view_events, confirm_events, capsule_opens
  ) values (
    p_author,
    p_drop_id,
    (timezone('utc', now()))::date,
    case when p_kind = 'view' then 1 else 0 end,
    case when p_kind = 'confirm' then 1 else 0 end,
    case when p_kind = 'open' then 1 else 0 end
  )
  on conflict (recipient_id, drop_id, notice_day) do update
    set view_events = n.view_events + excluded.view_events,
        confirm_events = n.confirm_events + excluded.confirm_events,
        capsule_opens = n.capsule_opens + excluded.capsule_opens;
end;
$$;

-- ---------------------------------------------------------------------------
-- Categories. Direct inserts: private.seed_category was removed with the taxonomy.
-- ---------------------------------------------------------------------------
insert into public.categories (
  group_id, slug, label, description, icon, color, attributes_schema,
  default_reveal_m, max_reveal_m, is_builtin, sort, posting_notice,
  content_filters, suggestion_hints, default_ttl, max_ttl, max_ttl_new
)
select g.id, v.slug, v.label, v.description, v.icon, g.color,
       '{"type":"object","additionalProperties":false,"properties":{}}'::jsonb,
       v.reveal_m, v.reveal_m, true, v.sort, v.notice,
       '{contact_info}'::text[], v.hints,
       v.default_ttl::interval, v.max_ttl::interval, v.max_ttl_new::interval
from (
  values
    ('history_stories', 'time_capsule', 'Time capsule',
     'A story that stays sealed until a date or until someone stands here.',
     '✉️', 150, 5, 'The story stays sealed until it unlocks.',
     'time capsule memory sealed story', null::text, null::text, null::text),
    ('community_faith', 'place', 'Place',
     'A business or organization someone can claim.',
     '🏪', 300, 4, 'Claiming a place is a separate step.',
     'place business organization store', null::text, null::text, null::text),
    ('events', 'special', 'Special',
     'An official announcement. It expires.',
     '📣', 300, 3, 'Official specials expire.',
     'special announcement official hours', '7 days', '30 days', '30 days')
) as v(group_slug, slug, label, description, icon, reveal_m, sort, notice, hints, default_ttl, max_ttl, max_ttl_new)
join public.category_groups g on g.slug = v.group_slug
where not exists (select 1 from public.categories c where c.slug = v.slug);

update public.categories
set embedding = private.hash_embed(
  replace(slug, '_', ' ') || ' ' || label || ' ' || coalesce(description, '') || ' ' || coalesce(suggestion_hints, '')
)
where slug in ('time_capsule', 'place', 'special') and embedding is null;

-- ---------------------------------------------------------------------------
-- Card fields. Official hours and the unlock condition are public.
-- The story is not.
-- ---------------------------------------------------------------------------
alter type public.drop_card add attribute official boolean;
alter type public.drop_card add attribute place_name text;
alter type public.drop_card add attribute official_summary text;
alter type public.drop_card add attribute official_hours jsonb;
alter type public.drop_card add attribute capsule_mode text;
alter type public.drop_card add attribute capsule_unlock_at timestamptz;
alter type public.drop_card add attribute capsule_opened_at timestamptz;
alter type public.drop_card add attribute oral_history boolean;
alter type public.drop_card add attribute capsule_locked boolean;

-- ---------------------------------------------------------------------------
-- Helpers.
-- ---------------------------------------------------------------------------
create function private.website_host(p_url text)
returns text
language plpgsql
immutable
set search_path = ''
as $$
declare
  v text := lower(btrim(coalesce(p_url, '')));
begin
  if v !~ '^https://' then
    return null;
  end if;
  v := substring(v from 9);
  v := split_part(split_part(split_part(v, '/', 1), '?', 1), ':', 1);
  if v like 'www.%' then
    v := substring(v from 5);
  end if;
  if v !~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$' then
    return null;
  end if;
  return v;
end;
$$;

create function private.free_email_domain(p_domain text)
returns boolean
language sql
immutable
set search_path = ''
as $$
  select lower(coalesce(p_domain, '')) in (
    'gmail.com', 'googlemail.com', 'outlook.com', 'hotmail.com', 'live.com', 'msn.com',
    'yahoo.com', 'ymail.com', 'icloud.com', 'me.com', 'mac.com', 'aol.com',
    'proton.me', 'protonmail.com', 'gmx.com', 'mail.com', 'zoho.com', 'yandex.com'
  );
$$;

create function private.caller_email_domain()
returns text
language sql
stable
security definer
set search_path = ''
as $$
  select case
    when position('@' in coalesce(u.email, '')) = 0 then null
    else lower(split_part(u.email, '@', 2))
  end
  from auth.users u
  where u.id = auth.uid();
$$;

create function private.assert_place_hours(p_hours jsonb)
returns void
language plpgsql
immutable
set search_path = ''
as $$
declare
  v_row jsonb;
  v_day integer;
  v_open text;
  v_close text;
begin
  if p_hours is null or jsonb_typeof(p_hours) <> 'array' then
    raise exception 'invalid_hours' using errcode = '22023', detail = 'hours must be a JSON array';
  end if;
  if jsonb_array_length(p_hours) > 14 then
    raise exception 'invalid_hours' using errcode = '22023', detail = 'at most 14 hour rows';
  end if;
  for v_row in select value from jsonb_array_elements(p_hours) loop
    if jsonb_typeof(v_row) <> 'object' then
      raise exception 'invalid_hours' using errcode = '22023', detail = 'each hour row is an object';
    end if;
    v_day := (v_row ->> 'day')::integer;
    v_open := v_row ->> 'open';
    v_close := v_row ->> 'close';
    if v_day is null or v_day not between 1 and 7
       or v_open !~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'
       or v_close !~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'
       or v_close <= v_open then
      raise exception 'invalid_hours' using errcode = '22023',
        detail = 'day is 1..7 (Monday..Sunday) and close is after open, HH:MM';
    end if;
  end loop;
end;
$$;

create function private.signed_in_profile()
returns public.profiles
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_profile public.profiles;
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
  return v_profile;
end;
$$;

create function private.place_member(p_place uuid, p_uid uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public.place_team_members m
    join public.place_teams t on t.id = m.team_id
    where t.place_id = p_place
      and t.status = 'active'
      and m.user_id = p_uid
      and m.status = 'active'
  );
$$;

create function private.active_claim(p_place uuid)
returns uuid
language sql
stable
security definer
set search_path = ''
as $$
  select cl.id
  from public.place_claims cl
  where cl.place_id = p_place and cl.status = 'active'
  limit 1;
$$;

-- Refuses a paid plan. One active team and one open claim are unique indexes.
create function private.activate_place_claim(p_claim uuid, p_method text)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_claim public.place_claims;
  v_team public.place_teams;
  v_plan public.place_plans;
  v_place public.places;
begin
  select * into v_claim from public.place_claims where id = p_claim for update;
  if not found or v_claim.status not in ('pending_proof', 'pending_review') then
    raise exception 'invalid_claim' using errcode = '22023';
  end if;
  select * into v_team from public.place_teams where id = v_claim.team_id for update;
  select * into v_plan from public.place_plans where slug = v_team.plan_slug;
  if v_plan.paid or not v_plan.active then
    raise exception 'plan_not_available' using errcode = '22023',
      detail = 'paid plans are not available';
  end if;
  if p_method is null or p_method not in ('email_domain', 'admin_review') then
    raise exception 'invalid_claim' using errcode = '22023';
  end if;

  update public.place_claims
  set status = 'active',
      proof_method = p_method,
      reviewed_by = case when p_method = 'admin_review' then auth.uid() else reviewed_by end
  where id = v_claim.id;

  update public.place_teams set status = 'active' where id = v_team.id and status = 'forming';

  update public.place_team_members
  set status = 'active'
  where team_id = v_team.id and user_id = v_claim.claimant_id and role = 'owner';

  select * into v_place from public.places where id = v_claim.place_id;
  update public.drops d
  set source = 'official'
  where d.id = v_place.anchor_drop_id
    and d.source = 'user';

  insert into public.place_official (place_id)
  values (v_place.id)
  on conflict (place_id) do nothing;
end;
$$;

create function private.dissolve_place_team(p_team uuid)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
  update public.place_teams set status = 'dissolved' where id = p_team and status <> 'dissolved';
  update public.place_team_members set status = 'removed' where team_id = p_team and status <> 'removed';
end;
$$;

create function private.clear_official_source(p_place uuid)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_anchor uuid;
begin
  select anchor_drop_id into v_anchor from public.places where id = p_place;
  update public.drops d
  set source = 'user'
  where d.source = 'official'
    and (d.id = v_anchor or d.place_id = p_place);
end;
$$;

create function private.official_json(p_place uuid, p_uid uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_place public.places;
  v_official public.place_official;
  v_plan text;
begin
  if private.active_claim(p_place) is null then
    return null;
  end if;
  select * into v_place from public.places where id = p_place;
  select * into v_official from public.place_official where place_id = p_place;
  select t.plan_slug into v_plan
  from public.place_teams t
  where t.place_id = p_place and t.status = 'active';
  return jsonb_build_object(
    'place_id', v_place.id,
    'name', v_place.name,
    'badge', 'Official',
    'summary', v_official.summary,
    'hours', coalesce(v_official.hours, '[]'::jsonb),
    'website_domain', v_place.website_domain,
    'anchor_drop_id', v_place.anchor_drop_id,
    'plan_slug', coalesce(v_plan, 'free'),
    'open_disputes', (
      select count(*)::integer from public.claim_disputes d
      where d.place_id = p_place and d.status = 'open'
    ),
    'can_reply', p_uid is not null and private.place_member(p_place, p_uid)
  );
end;
$$;

-- Copies the secret onto the drop once. Stable reads must not call this.
create function private.release_capsule(
  p_drop_id uuid,
  p_uid uuid,
  p_point extensions.geography,
  p_credit boolean,
  p_reasons jsonb
)
returns boolean
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_drop public.drops;
  v_body text;
  v_media jsonb;
  v_distance double precision;
  v_release boolean := false;
begin
  select * into v_drop from public.drops where id = p_drop_id for update;
  if not found or v_drop.capsule_mode is null or v_drop.capsule_opened_at is not null then
    return false;
  end if;

  if v_drop.capsule_mode = 'date' then
    v_release := v_drop.capsule_unlock_at <= now();
  elsif v_drop.capsule_mode = 'visit'
     or (v_drop.capsule_mode = 'both' and v_drop.capsule_unlock_at <= now()) then
    if p_uid is not null
       and p_uid is distinct from v_drop.author_id
       and p_point is not null then
      v_distance := extensions.st_distance(v_drop.geog, p_point);
      if v_distance <= 30 then
        if coalesce(p_credit, false) then
          v_release := true;
        else
          perform private.record_location_flag(p_uid, 'open_capsule', v_drop.id, p_reasons);
        end if;
      end if;
    end if;
  end if;

  if not v_release then
    return false;
  end if;

  select s.body, s.media into v_body, v_media
  from private.capsule_secrets s
  where s.drop_id = v_drop.id;

  update public.drops
  set body = v_body,
      capsule_opened_at = now(),
      capsule_opened_by = p_uid
  where id = v_drop.id;

  insert into public.drop_media (
    drop_id, owner_id, storage_bucket, storage_path, media_kind, mime_type,
    byte_size, width, height, duration_s, sort, taken_here, alt_text
  )
  select v_drop.id, v_drop.author_id,
         coalesce(e.value ->> 'storage_bucket', 'drop-media'),
         e.value ->> 'storage_path',
         (e.value ->> 'media_kind')::public.media_kind,
         e.value ->> 'mime_type',
         nullif(e.value ->> 'byte_size', '')::bigint,
         nullif(e.value ->> 'width', '')::integer,
         nullif(e.value ->> 'height', '')::integer,
         nullif(e.value ->> 'duration_s', '')::numeric,
         (e.ord - 1)::smallint,
         false,
         nullif(left(btrim(e.value ->> 'alt_text'), 300), '')
  from jsonb_array_elements(coalesce(v_media, '[]'::jsonb)) with ordinality as e(value, ord);

  delete from private.capsule_secrets where drop_id = v_drop.id;
  perform private.note_drop_helped(v_drop.author_id, v_drop.id, 'open');
  return true;
end;
$$;

create function private.release_due_capsules()
returns integer
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_id uuid;
  v_count integer := 0;
begin
  for v_id in
    select d.id
    from public.drops d
    where d.capsule_mode = 'date'
      and d.capsule_opened_at is null
      and d.capsule_unlock_at <= now()
    order by d.capsule_unlock_at
    limit 200
  loop
    if private.release_capsule(v_id, null, null, false, '[]'::jsonb) then
      v_count := v_count + 1;
    end if;
  end loop;
  return v_count;
end;
$$;

create function private.validate_capsule_media(p_media jsonb, p_uid uuid)
returns jsonb
language plpgsql
stable
set search_path = ''
as $$
declare
  v_item jsonb;
  v_kind text;
  v_bucket text;
  v_mime text;
  v_seconds numeric;
  v_bytes bigint;
begin
  if p_media is null or jsonb_typeof(p_media) <> 'array' then
    raise exception 'invalid_media' using errcode = '22023', detail = 'media must be a JSON array';
  end if;
  if jsonb_array_length(p_media) > 8 then
    raise exception 'invalid_media' using errcode = '22023', detail = 'at most 8 attachments';
  end if;
  for v_item in select value from jsonb_array_elements(p_media) loop
    v_kind := v_item ->> 'media_kind';
    v_bucket := coalesce(v_item ->> 'storage_bucket', 'drop-media');
    v_mime := coalesce(v_item ->> 'mime_type', '');
    if jsonb_typeof(v_item) <> 'object'
       or coalesce(v_item ->> 'storage_path', '') = ''
       or split_part(v_item ->> 'storage_path', '/', 1) <> p_uid::text
       or v_mime = ''
       or v_kind is null
       or not (v_kind = any (enum_range(null::public.media_kind)::text[])) then
      raise exception 'invalid_media' using errcode = '22023',
        detail = 'each item needs media_kind, mime_type, and a storage_path under "<your user id>/"';
    end if;
    if v_kind = 'audio' then
      if v_bucket is distinct from 'voice-clips'
         or v_mime not in ('audio/mp4', 'audio/aac', 'audio/3gpp', 'audio/amr') then
        raise exception 'invalid_media' using errcode = '22023',
          detail = 'audio uses the voice-clips bucket and a compressed type';
      end if;
      v_seconds := nullif(v_item ->> 'duration_s', '')::numeric;
      v_bytes := nullif(v_item ->> 'byte_size', '')::bigint;
      if v_seconds is null or v_seconds <= 0 or v_seconds > 30 then
        raise exception 'voice_too_long' using errcode = '22023', detail = 'audio is at most 30 seconds';
      end if;
      if v_bytes is null or v_bytes > 262144 then
        raise exception 'voice_too_large' using errcode = '22023', detail = 'audio is at most 256 KB';
      end if;
    end if;
  end loop;
  return p_media;
end;
$$;

-- ---------------------------------------------------------------------------
-- Card. Official announcements read like a public record while the claim
-- is active. A locked capsule never copies its story, even for the author.
-- ---------------------------------------------------------------------------
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
  v_place uuid;
  v_official boolean := false;
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
  v_card.location_trust := p_drop.location_trust;
  v_card.location_withheld := false;
  v_card.official := false;
  v_card.official_hours := '[]'::jsonb;
  v_card.oral_history := p_drop.oral_history;
  v_card.capsule_mode := p_drop.capsule_mode;
  v_card.capsule_unlock_at := p_drop.capsule_unlock_at;
  v_card.capsule_opened_at := p_drop.capsule_opened_at;
  v_card.capsule_locked := p_drop.capsule_mode is not null and p_drop.capsule_opened_at is null;

  if p_viewer is not null then
    select c.verdict into v_card.my_verdict
    from public.confirmations c
    where c.drop_id = p_drop.id and c.user_id = p_viewer and c.counts_for_credit;
  end if;

  if p_drop.author_id is not null
     and not (p_drop.display_anonymous and v_category.allow_anonymous_display) then
    select p.handle, nullif(btrim(p.display_name), '')
      into v_card.author_handle, v_card.author_display_name
    from public.profiles p where p.id = p_drop.author_id;
    v_card.author_badges := private.author_badges_json(p_drop.author_id, p_drop.geog);
    v_card.author_tier := (select p.tier from public.profiles p where p.id = p_drop.author_id);
  end if;

  select s.name, i.source_url, i.license, i.attribution
    into v_card.source_name, v_card.source_url, v_card.license, v_card.attribution
  from public.open_data_items i
  join public.open_data_sources s on s.slug = i.source_slug
  where i.drop_id = p_drop.id;

  v_place := p_drop.place_id;
  if v_place is null then
    select pl.id into v_place from public.places pl where pl.anchor_drop_id = p_drop.id;
  end if;
  if v_place is not null and private.active_claim(v_place) is not null then
    v_official := true;
    select pl.name, po.summary, coalesce(po.hours, '[]'::jsonb)
      into v_card.place_name, v_card.official_summary, v_card.official_hours
    from public.places pl
    left join public.place_official po on po.place_id = pl.id
    where pl.id = v_place;
  end if;
  v_card.official := v_official;

  if p_point is not null then
    v_distance := extensions.st_distance(p_drop.display_geog, p_point);
    v_card.distance_m := round(v_distance::numeric, 1)::double precision;
  end if;

  v_card.revealed := p_drop.source = 'open_data'
    or (p_drop.source = 'official' and v_official)
    or v_card.is_mine
    or (v_distance is not null and v_distance <= p_drop.reveal_radius_m);

  if v_card.capsule_locked then
    v_card.revealed := false;
  end if;

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
          'taken_here', m.taken_here,
          'alt_text', m.alt_text
        ) order by m.sort, m.created_at)
      from public.drop_media m
      where m.drop_id = p_drop.id
    ), '[]'::jsonb);
  end if;

  return v_card;
end;
$$;

-- Reveal, and the only client path that can open a capsule.
drop function public.drop_detail(uuid, double precision, double precision, jsonb);

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

  if v_drop.capsule_mode is not null and v_drop.capsule_opened_at is null then
    perform private.release_capsule(
      v_drop.id, v_uid, v_point, v_credit, coalesce(v_decision -> 'reasons', '[]'::jsonb)
    );
    select * into v_drop from public.drops d where d.id = p_drop_id;
  end if;

  v_card := private.to_drop_card(v_drop, v_uid, v_point);
  v_withhold := not v_credit
    and not v_card.is_mine
    and not v_card.capsule_locked
    and v_drop.source is distinct from 'open_data'
    and v_drop.source is distinct from 'official'
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
  elsif not v_card.capsule_locked then
    v_drop.view_count := private.record_drop_view(v_drop.id, v_drop.author_id);
    v_card.view_count := v_drop.view_count;
  end if;
  if v_card.capsule_locked then
    v_card.location_withheld := false;
    v_card.revealed := false;
    v_card.body := null;
    v_card.attributes := null;
    v_card.tags := null;
    v_card.media := null;
  end if;
  if v_uid is not null and v_card.revealed and v_card.media is not null then
    perform private.grant_drop_media(v_drop.id, v_uid);
  end if;
  return v_card;
end;
$$;

-- Map pins. sealed is true while the story is still hidden. Clusters are not sealed.
drop function public.drops_in_view(
  double precision, double precision, double precision, double precision, text[], integer
);

create function public.drops_in_view(
  p_min_lng double precision,
  p_min_lat double precision,
  p_max_lng double precision,
  p_max_lat double precision,
  p_categories text[] default null,
  p_zoom integer default 16
)
returns table (
  kind text,
  drop_id uuid,
  short_id text,
  category_slug text,
  group_slug text,
  title text,
  lat double precision,
  lng double precision,
  point_count integer,
  category_counts jsonb,
  source text,
  sealed boolean
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_envelope extensions.geography;
  v_cell double precision;
begin
  perform private.make_point(p_min_lat, p_min_lng);
  perform private.make_point(p_max_lat, p_max_lng);
  if p_min_lng >= p_max_lng or p_min_lat >= p_max_lat then
    raise exception 'invalid_bbox' using errcode = '22023', detail = 'min must be below max on both axes';
  end if;
  if p_zoom is null or p_zoom not between 0 and 22 then
    raise exception 'invalid_zoom' using errcode = '22023', detail = 'zoom must be 0..22';
  end if;

  v_envelope := extensions.st_makeenvelope(p_min_lng, p_min_lat, p_max_lng, p_max_lat, 4326)::extensions.geography;

  if p_zoom >= 15 then
    return query
    select 'drop'::text, d.id, d.short_id, c.slug, g.slug, d.title,
           extensions.st_y(d.display_geog::extensions.geometry),
           extensions.st_x(d.display_geog::extensions.geometry),
           1, null::jsonb, d.source::text,
           (d.capsule_mode is not null and d.capsule_opened_at is null)
    from public.drops d
    join public.categories c on c.id = d.category_id
    join public.category_groups g on g.id = c.group_id
    where extensions.st_intersects(d.display_geog, v_envelope)
      and (p_categories is null or c.slug = any (p_categories) or g.slug = any (p_categories))
      and private.can_view_drop(d, v_uid)
    order by d.created_at desc
    limit 500;
    return;
  end if;

  v_cell := 360.0 / power(2, p_zoom) / 8;

  return query
  with visible as (
    select d.id, d.short_id, d.title, c.slug as cslug, g.slug as gslug, d.source::text as source,
           (d.capsule_mode is not null and d.capsule_opened_at is null) as sealed,
           extensions.st_y(d.display_geog::extensions.geometry) as y,
           extensions.st_x(d.display_geog::extensions.geometry) as x
    from public.drops d
    join public.categories c on c.id = d.category_id
    join public.category_groups g on g.id = c.group_id
    where extensions.st_intersects(d.display_geog, v_envelope)
      and (p_categories is null or c.slug = any (p_categories) or g.slug = any (p_categories))
      and private.can_view_drop(d, v_uid)
  ),
  per_category as (
    select floor(v.x / v_cell) as cx, floor(v.y / v_cell) as cy, v.cslug, count(*)::integer as n
    from visible v
    group by 1, 2, 3
  ),
  cells as (
    select pc.cx, pc.cy, jsonb_object_agg(pc.cslug, pc.n) as counts
    from per_category pc
    group by pc.cx, pc.cy
  ),
  grouped as (
    select floor(v.x / v_cell) as cx, floor(v.y / v_cell) as cy,
           count(*)::integer as n,
           avg(v.y) as y, avg(v.x) as x,
           (array_agg(v.id))[1] as id,
           (array_agg(v.short_id))[1] as short_id,
           (array_agg(v.cslug))[1] as cslug,
           (array_agg(v.gslug))[1] as gslug,
           (array_agg(v.title))[1] as title,
           (array_agg(v.source))[1] as source,
           bool_or(v.sealed) filter (where v.sealed) as any_sealed,
           (array_agg(v.sealed))[1] as first_sealed
    from visible v
    group by 1, 2
  )
  select case when gr.n = 1 then 'drop' else 'cluster' end,
         case when gr.n = 1 then gr.id end,
         case when gr.n = 1 then gr.short_id end,
         case when gr.n = 1 then gr.cslug end,
         case when gr.n = 1 then gr.gslug end,
         case when gr.n = 1 then gr.title end,
         gr.y, gr.x, gr.n, ce.counts,
         case when gr.n = 1 then gr.source end,
         case when gr.n = 1 then gr.first_sealed else false end
  from grouped gr
  join cells ce on ce.cx = gr.cx and ce.cy = gr.cy
  order by gr.n desc
  limit 1000;
end;
$$;

drop function public.my_impact_notices(integer);

create function public.my_impact_notices(p_limit integer default 30)
returns table (
  id uuid,
  drop_id uuid,
  short_id text,
  title text,
  notice_day date,
  view_events integer,
  confirm_events integer,
  read_at timestamptz,
  created_at timestamptz,
  capsule_opens integer
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_limit integer := least(greatest(coalesce(p_limit, 30), 1), 50);
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;

  return query
  select n.id, n.drop_id, d.short_id, d.title, n.notice_day,
         n.view_events, n.confirm_events, n.read_at, n.created_at, n.capsule_opens
  from public.impact_notices n
  join public.drops d on d.id = n.drop_id
  where n.recipient_id = v_uid
  order by (n.read_at is null) desc, n.updated_at desc
  limit v_limit;
end;
$$;

-- ---------------------------------------------------------------------------
-- Places and claims.
-- ---------------------------------------------------------------------------
create function public.create_user_place(
  p_name text,
  p_lat double precision,
  p_lng double precision,
  p_website text default null,
  p_integrity jsonb default null
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_profile public.profiles;
  v_category public.categories;
  v_point extensions.geography;
  v_name text := btrim(coalesce(p_name, ''));
  v_host text;
  v_decision jsonb;
  v_credit boolean;
  v_drop_id uuid;
  v_place_id uuid;
  v_short text;
begin
  v_profile := private.signed_in_profile();
  if char_length(v_name) not between 1 and 120 then
    raise exception 'invalid_title' using errcode = '22023', detail = 'name must be 1..120 characters';
  end if;
  perform private.assert_content_allowed('{contact_info}'::text[], array[v_name]);
  v_host := private.website_host(p_website);
  if p_website is not null and btrim(p_website) <> '' and v_host is null then
    raise exception 'invalid_website' using errcode = '22023', detail = 'website must be https';
  end if;
  select * into v_category from public.categories c where c.slug = 'place' and c.active;
  if not found then
    raise exception 'category_not_found' using errcode = '22023', detail = 'place';
  end if;
  v_point := private.make_point(p_lat, p_lng);
  v_decision := private.location_decision(v_profile.id, 'create_drop', v_point, p_integrity);
  v_credit := coalesce((v_decision ->> 'credit')::boolean, true);
  v_drop_id := gen_random_uuid();
  if not v_credit then
    perform private.record_location_flag(v_profile.id, 'create_drop', v_drop_id, v_decision -> 'reasons');
  end if;

  insert into public.drops (
    id, author_id, category_id, category_version, source, title, body, attributes, tags,
    geog, display_geog, reveal_radius_m, status, display_anonymous,
    location_trust, counts_for_credit
  ) values (
    v_drop_id, v_profile.id, v_category.id, v_category.version, 'user', v_name, null, '{}'::jsonb, '{}',
    v_point, private.fuzz_point(v_point, v_category.location_precision_m),
    v_category.default_reveal_m, 'active', false,
    case when v_credit then 'standard' else 'reduced' end,
    v_credit
  )
  returning short_id into v_short;

  insert into public.places (kind, anchor_drop_id, name, website_url, website_domain, geog, created_by)
  values ('user', v_drop_id, v_name, case when v_host is null then null else btrim(p_website) end, v_host, v_point, v_profile.id)
  returning id into v_place_id;

  update public.drops set place_id = v_place_id where id = v_drop_id;

  return jsonb_build_object(
    'place_id', v_place_id,
    'drop_id', v_drop_id,
    'short_id', v_short
  );
end;
$$;

create function public.start_place_claim(
  p_anchor_drop_id uuid,
  p_website text default null,
  p_lat double precision default null,
  p_lng double precision default null,
  p_integrity jsonb default null
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_profile public.profiles;
  v_drop public.drops;
  v_category text;
  v_point extensions.geography;
  v_distance double precision;
  v_decision jsonb;
  v_host text;
  v_place public.places;
  v_team uuid;
  v_claim uuid;
begin
  v_profile := private.signed_in_profile();
  select * into v_drop from public.drops where id = p_anchor_drop_id;
  if not found then
    raise exception 'drop_not_found' using errcode = 'P0002';
  end if;
  select c.slug into v_category from public.categories c where c.id = v_drop.category_id;
  if v_drop.source is distinct from 'open_data' and v_category is distinct from 'place' then
    raise exception 'not_a_place' using errcode = '22023';
  end if;
  v_point := private.make_point(p_lat, p_lng);
  v_distance := extensions.st_distance(v_drop.geog, v_point);
  if v_distance > 50 then
    raise exception 'too_far' using errcode = '22023', detail = 'stand within 50 m of the place';
  end if;
  v_decision := private.location_decision(v_profile.id, 'claim_place', v_point, p_integrity);
  if not coalesce((v_decision ->> 'credit')::boolean, false) then
    perform private.record_location_flag(v_profile.id, 'claim_place', v_drop.id, v_decision -> 'reasons');
    raise exception 'location_not_trusted' using errcode = '22023';
  end if;

  v_host := private.website_host(p_website);
  if p_website is not null and btrim(p_website) <> '' and v_host is null then
    raise exception 'invalid_website' using errcode = '22023', detail = 'website must be https';
  end if;

  select * into v_place from public.places where anchor_drop_id = v_drop.id;
  if not found then
    insert into public.places (kind, anchor_drop_id, name, website_url, website_domain, geog, created_by)
    values (
      case when v_drop.source = 'open_data' then 'open_data' else 'user' end,
      v_drop.id, v_drop.title,
      case when v_host is null then null else btrim(p_website) end,
      v_host, v_drop.geog, v_profile.id
    )
    returning * into v_place;
    update public.drops set place_id = v_place.id where id = v_drop.id and place_id is null;
  elsif v_host is not null and v_place.website_domain is null then
    update public.places
    set website_url = btrim(p_website), website_domain = v_host
    where id = v_place.id
    returning * into v_place;
  end if;

  if exists (
    select 1 from public.place_claims cl
    where cl.place_id = v_place.id
      and cl.status in ('pending_proof', 'pending_review', 'active')
  ) then
    raise exception 'place_already_claimed' using errcode = '22023';
  end if;

  insert into public.place_teams (place_id, plan_slug, status)
  values (v_place.id, 'free', 'forming')
  returning id into v_team;

  insert into public.place_team_members (team_id, user_id, role, status, invited_by)
  values (v_team, v_profile.id, 'owner', 'pending', v_profile.id);

  insert into public.place_claims (
    place_id, team_id, claimant_id, status, onsite_distance_m, email_domain
  ) values (
    v_place.id, v_team, v_profile.id, 'pending_proof', v_distance, v_place.website_domain
  )
  returning id into v_claim;

  return jsonb_build_object(
    'claim_id', v_claim,
    'place_id', v_place.id,
    'team_id', v_team,
    'status', 'pending_proof',
    'plan_slug', 'free'
  );
end;
$$;

create function public.prove_claim_email(p_claim_id uuid)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_profile public.profiles;
  v_claim public.place_claims;
  v_place public.places;
  v_email text;
begin
  v_profile := private.signed_in_profile();
  select * into v_claim from public.place_claims where id = p_claim_id;
  if not found or v_claim.claimant_id <> v_profile.id or v_claim.status <> 'pending_proof' then
    raise exception 'invalid_claim' using errcode = '22023';
  end if;
  select * into v_place from public.places where id = v_claim.place_id;
  v_email := private.caller_email_domain();
  if v_place.website_domain is null
     or v_email is null
     or private.free_email_domain(v_email)
     or v_email <> v_place.website_domain then
    raise exception 'email_domain_mismatch' using errcode = '22023';
  end if;
  update public.place_claims set email_domain = v_email where id = v_claim.id;
  perform private.activate_place_claim(v_claim.id, 'email_domain');
  return jsonb_build_object(
    'claim_id', v_claim.id,
    'place_id', v_place.id,
    'team_id', v_claim.team_id,
    'status', 'active',
    'proof_method', 'email_domain',
    'plan_slug', 'free'
  );
end;
$$;

-- TODO: no paid mailer. A later job can print this code. Do not return it and do not send it.
create function public.request_claim_postcard(p_claim_id uuid)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_profile public.profiles;
  v_claim public.place_claims;
  v_code text;
begin
  v_profile := private.signed_in_profile();
  select * into v_claim from public.place_claims where id = p_claim_id for update;
  if not found or v_claim.claimant_id <> v_profile.id or v_claim.status <> 'pending_proof' then
    raise exception 'invalid_claim' using errcode = '22023';
  end if;
  v_code := substr(md5(gen_random_uuid()::text || clock_timestamp()::text), 1, 8);
  update public.place_claims
  set postcard_requested_at = now(),
      postcard_code_hash = md5(v_code || id::text),
      proof_method = 'postcard'
  where id = v_claim.id;
  return jsonb_build_object(
    'requested', true,
    'mailed', false,
    'status', 'pending_proof'
  );
end;
$$;

create function public.submit_claim_for_review(p_claim_id uuid, p_note text default null)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_profile public.profiles;
  v_claim public.place_claims;
  v_note text := nullif(btrim(coalesce(p_note, '')), '');
begin
  v_profile := private.signed_in_profile();
  if v_note is not null and char_length(v_note) > 500 then
    raise exception 'invalid_note' using errcode = '22023';
  end if;
  select * into v_claim from public.place_claims where id = p_claim_id for update;
  if not found or v_claim.claimant_id <> v_profile.id or v_claim.status <> 'pending_proof' then
    raise exception 'invalid_claim' using errcode = '22023';
  end if;
  update public.place_claims
  set status = 'pending_review',
      proof_method = 'admin_review',
      review_note = v_note
  where id = v_claim.id;
  return jsonb_build_object(
    'claim_id', v_claim.id,
    'place_id', v_claim.place_id,
    'team_id', v_claim.team_id,
    'status', 'pending_review',
    'proof_method', 'admin_review',
    'plan_slug', 'free'
  );
end;
$$;

create function public.review_place_claim(p_claim_id uuid, p_approve boolean, p_note text default null)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_claim public.place_claims;
  v_note text := nullif(btrim(coalesce(p_note, '')), '');
begin
  perform private.signed_in_profile();
  if not private.is_admin() then
    raise exception 'not_admin' using errcode = '42501';
  end if;
  select * into v_claim from public.place_claims where id = p_claim_id;
  if not found or v_claim.status <> 'pending_review' then
    raise exception 'invalid_claim' using errcode = '22023';
  end if;
  if coalesce(p_approve, false) then
    perform private.activate_place_claim(v_claim.id, 'admin_review');
    if v_note is not null then
      update public.place_claims set review_note = v_note where id = v_claim.id;
    end if;
    return jsonb_build_object('claim_id', v_claim.id, 'place_id', v_claim.place_id, 'status', 'active', 'plan_slug', 'free');
  end if;
  update public.place_claims
  set status = 'rejected', review_note = coalesce(v_note, review_note), reviewed_by = auth.uid()
  where id = v_claim.id;
  perform private.dissolve_place_team(v_claim.team_id);
  return jsonb_build_object('claim_id', v_claim.id, 'place_id', v_claim.place_id, 'status', 'rejected', 'plan_slug', 'free');
end;
$$;

create function public.revoke_place_claim(p_claim_id uuid, p_reason text)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_claim public.place_claims;
  v_reason text := btrim(coalesce(p_reason, ''));
begin
  perform private.signed_in_profile();
  if not private.is_admin() then
    raise exception 'not_admin' using errcode = '42501';
  end if;
  if char_length(v_reason) not between 1 and 500 then
    raise exception 'invalid_note' using errcode = '22023';
  end if;
  select * into v_claim from public.place_claims where id = p_claim_id for update;
  if not found or v_claim.status <> 'active' then
    raise exception 'invalid_claim' using errcode = '22023';
  end if;
  update public.place_claims
  set status = 'revoked', review_note = v_reason, reviewed_by = auth.uid()
  where id = v_claim.id;
  perform private.dissolve_place_team(v_claim.team_id);
  perform private.clear_official_source(v_claim.place_id);
  return jsonb_build_object('claim_id', v_claim.id, 'place_id', v_claim.place_id, 'status', 'revoked');
end;
$$;

create function public.invite_place_coowner(p_place_id uuid, p_handle text)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_profile public.profiles;
  v_team uuid;
  v_invitee uuid;
  v_handle text := lower(btrim(coalesce(p_handle, '')));
begin
  v_profile := private.signed_in_profile();
  if not private.place_member(p_place_id, v_profile.id) then
    raise exception 'not_owner' using errcode = '42501';
  end if;
  if not exists (
    select 1 from public.place_team_members m
    join public.place_teams t on t.id = m.team_id
    where t.place_id = p_place_id and t.status = 'active'
      and m.user_id = v_profile.id and m.role = 'owner' and m.status = 'active'
  ) then
    raise exception 'not_owner' using errcode = '42501';
  end if;
  select id into v_invitee from public.profiles where handle = v_handle and deleted_at is null;
  if v_invitee is null then
    raise exception 'user_not_found' using errcode = 'P0002';
  end if;
  if v_invitee = v_profile.id then
    raise exception 'invalid_invite' using errcode = '22023';
  end if;
  select t.id into v_team from public.place_teams t where t.place_id = p_place_id and t.status = 'active';
  insert into public.place_team_members (team_id, user_id, role, status, invited_by)
  values (v_team, v_invitee, 'co_owner', 'invited', v_profile.id)
  on conflict (team_id, user_id) do update
    set status = 'invited', role = 'co_owner', invited_by = excluded.invited_by
    where public.place_team_members.status = 'removed';
  return jsonb_build_object('place_id', p_place_id, 'user_id', v_invitee, 'status', 'invited');
end;
$$;

create function public.accept_place_invite(p_place_id uuid)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_profile public.profiles;
  v_team uuid;
begin
  v_profile := private.signed_in_profile();
  select t.id into v_team from public.place_teams t where t.place_id = p_place_id and t.status = 'active';
  if v_team is null then
    raise exception 'invalid_invite' using errcode = '22023';
  end if;
  update public.place_team_members
  set status = 'active'
  where team_id = v_team and user_id = v_profile.id and status = 'invited';
  if not found then
    raise exception 'invalid_invite' using errcode = '22023';
  end if;
  return jsonb_build_object('place_id', p_place_id, 'status', 'active');
end;
$$;

create function public.set_place_hours(p_place_id uuid, p_hours jsonb, p_summary text default null)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_profile public.profiles;
  v_summary text := nullif(btrim(coalesce(p_summary, '')), '');
begin
  v_profile := private.signed_in_profile();
  if private.active_claim(p_place_id) is null or not private.place_member(p_place_id, v_profile.id) then
    raise exception 'not_owner' using errcode = '42501';
  end if;
  perform private.assert_place_hours(coalesce(p_hours, '[]'::jsonb));
  if v_summary is not null and char_length(v_summary) > 500 then
    raise exception 'invalid_body' using errcode = '22023';
  end if;
  perform private.assert_content_allowed('{contact_info}'::text[], array[v_summary]);
  insert into public.place_official (place_id, hours, summary, updated_by)
  values (p_place_id, coalesce(p_hours, '[]'::jsonb), v_summary, v_profile.id)
  on conflict (place_id) do update
    set hours = excluded.hours,
        summary = excluded.summary,
        updated_by = excluded.updated_by,
        updated_at = now();
  return private.official_json(p_place_id, v_profile.id);
end;
$$;

create function public.post_place_announcement(
  p_place_id uuid,
  p_title text,
  p_body text,
  p_expires_at timestamptz,
  p_lat double precision,
  p_lng double precision,
  p_integrity jsonb default null
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_profile public.profiles;
  v_place public.places;
  v_category public.categories;
  v_limits public.tier_limits;
  v_point extensions.geography;
  v_title text := btrim(coalesce(p_title, ''));
  v_body text := nullif(btrim(coalesce(p_body, '')), '');
  v_decision jsonb;
  v_credit boolean;
  v_last_hour integer;
  v_last_day integer;
  v_id uuid;
  v_short text;
begin
  v_profile := private.signed_in_profile();
  if private.active_claim(p_place_id) is null or not private.place_member(p_place_id, v_profile.id) then
    raise exception 'not_owner' using errcode = '42501';
  end if;
  select * into v_place from public.places where id = p_place_id;
  if char_length(v_title) not between 1 and 120 then
    raise exception 'invalid_title' using errcode = '22023';
  end if;
  if v_body is null or char_length(v_body) > 2000 then
    raise exception 'invalid_body' using errcode = '22023';
  end if;
  if p_expires_at is null or p_expires_at <= now() or p_expires_at > now() + interval '30 days' then
    raise exception 'expires_too_far' using errcode = '22023', detail = 'a special expires within 30 days';
  end if;
  perform private.assert_content_allowed('{contact_info}'::text[], array[v_title, v_body]);
  v_point := private.make_point(p_lat, p_lng);
  if extensions.st_distance(v_place.geog, v_point) > 80 then
    raise exception 'too_far' using errcode = '22023', detail = 'post within 80 m of the place';
  end if;
  select * into v_category from public.categories where slug = 'special' and active;
  perform pg_advisory_xact_lock(hashtextextended('xhere.create_drop:' || v_profile.id::text, 0));
  select * into v_limits from public.tier_limits where tier = v_profile.tier;
  select count(*) filter (where d.created_at > now() - interval '1 hour'), count(*)
    into v_last_hour, v_last_day
  from public.drops d
  where d.author_id = v_profile.id and d.created_at > now() - interval '1 day';
  if v_last_hour >= coalesce(v_limits.drops_per_hour, 0) or v_last_day >= coalesce(v_limits.drops_per_day, 0) then
    raise exception 'rate_limited' using errcode = 'PT429';
  end if;
  v_decision := private.location_decision(v_profile.id, 'create_drop', v_point, p_integrity);
  v_credit := coalesce((v_decision ->> 'credit')::boolean, true);
  v_id := gen_random_uuid();
  if not v_credit then
    perform private.record_location_flag(v_profile.id, 'create_drop', v_id, v_decision -> 'reasons');
  end if;
  insert into public.drops (
    id, author_id, category_id, category_version, source, title, body, attributes, tags,
    geog, display_geog, reveal_radius_m, status, expires_at, place_id,
    location_trust, counts_for_credit
  ) values (
    v_id, v_profile.id, v_category.id, v_category.version, 'official', v_title, v_body, '{}'::jsonb, '{}',
    v_point, v_point, v_category.default_reveal_m, 'active', p_expires_at, p_place_id,
    case when v_credit then 'standard' else 'reduced' end,
    v_credit
  )
  returning short_id into v_short;
  return jsonb_build_object('id', v_id, 'short_id', v_short, 'place_id', p_place_id);
end;
$$;

create function public.reply_as_owner(p_drop_id uuid, p_body text)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_profile public.profiles;
  v_drop public.drops;
  v_body text := btrim(coalesce(p_body, ''));
  v_place uuid;
  v_id uuid;
  v_created timestamptz;
begin
  v_profile := private.signed_in_profile();
  if char_length(v_body) not between 1 and 500 then
    raise exception 'invalid_body' using errcode = '22023';
  end if;
  perform private.assert_content_allowed('{contact_info}'::text[], array[v_body]);
  select * into v_drop from public.drops where id = p_drop_id;
  if not found or not private.can_view_drop(v_drop, v_profile.id) then
    raise exception 'drop_not_found' using errcode = 'P0002';
  end if;
  select pl.id into v_place
  from public.places pl
  where private.active_claim(pl.id) is not null
    and private.place_member(pl.id, v_profile.id)
    and extensions.st_dwithin(pl.geog, v_drop.geog, 80)
  order by extensions.st_distance(pl.geog, v_drop.geog)
  limit 1;
  if v_place is null then
    raise exception 'not_owner' using errcode = '42501';
  end if;
  insert into public.owner_replies (place_id, drop_id, author_id, body)
  values (v_place, v_drop.id, v_profile.id, v_body)
  returning id, created_at into v_id, v_created;
  return jsonb_build_object(
    'id', v_id,
    'place_id', v_place,
    'drop_id', v_drop.id,
    'author_handle', v_profile.handle,
    'body', v_body,
    'created_at', v_created
  );
end;
$$;

create function public.owner_replies_for_drop(p_drop_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  perform private.signed_in_profile();
  return coalesce((
    select jsonb_agg(jsonb_build_object(
      'id', r.id,
      'place_id', r.place_id,
      'drop_id', r.drop_id,
      'author_handle', p.handle,
      'body', r.body,
      'created_at', r.created_at
    ) order by r.created_at)
    from public.owner_replies r
    join public.profiles p on p.id = r.author_id
    where r.drop_id = p_drop_id
  ), '[]'::jsonb);
end;
$$;

create function public.dispute_place_claim(p_place_id uuid, p_reason text, p_details text default null)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_profile public.profiles;
  v_claim uuid;
  v_reason text := btrim(coalesce(p_reason, ''));
  v_details text := nullif(btrim(coalesce(p_details, '')), '');
  v_id uuid;
begin
  v_profile := private.signed_in_profile();
  if char_length(v_reason) not between 1 and 80 then
    raise exception 'invalid_report' using errcode = '22023';
  end if;
  if v_details is not null and char_length(v_details) > 500 then
    raise exception 'invalid_report' using errcode = '22023';
  end if;
  if private.place_member(p_place_id, v_profile.id) then
    raise exception 'not_allowed' using errcode = '42501';
  end if;
  v_claim := private.active_claim(p_place_id);
  if v_claim is null then
    raise exception 'invalid_claim' using errcode = '22023';
  end if;
  insert into public.claim_disputes (claim_id, place_id, reporter_id, reason, details)
  values (v_claim, p_place_id, v_profile.id, v_reason, v_details)
  returning id into v_id;
  return jsonb_build_object('id', v_id, 'place_id', p_place_id, 'status', 'open');
end;
$$;

create function public.claim_review_queue(p_limit integer default 50)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_limit integer := least(greatest(coalesce(p_limit, 50), 1), 100);
begin
  perform private.signed_in_profile();
  if not private.is_admin() then
    raise exception 'not_admin' using errcode = '42501';
  end if;
  return coalesce((
    select jsonb_agg(row_to_json(q) order by q.created_at)
    from (
      select 'claim'::text as kind, cl.id, cl.place_id, pl.name as place_name, cl.status,
             pr.handle as claimant_handle, cl.proof_method, pl.website_domain, cl.review_note as note,
             cl.created_at
      from public.place_claims cl
      join public.places pl on pl.id = cl.place_id
      join public.profiles pr on pr.id = cl.claimant_id
      where cl.status = 'pending_review'
      union all
      select 'dispute'::text, d.id, d.place_id, pl.name, d.status,
             pr.handle, null::text, pl.website_domain, d.reason, d.created_at
      from public.claim_disputes d
      join public.places pl on pl.id = d.place_id
      join public.profiles pr on pr.id = d.reporter_id
      where d.status = 'open'
      order by created_at
      limit v_limit
    ) q
  ), '[]'::jsonb);
end;
$$;

create function public.resolve_claim_dispute(p_dispute_id uuid, p_dismiss boolean)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_row public.claim_disputes;
  v_status text;
begin
  perform private.signed_in_profile();
  if not private.is_admin() then
    raise exception 'not_admin' using errcode = '42501';
  end if;
  select * into v_row from public.claim_disputes where id = p_dispute_id and status = 'open';
  if not found then
    raise exception 'report_not_found' using errcode = 'P0002';
  end if;
  v_status := case when coalesce(p_dismiss, false) then 'dismissed' else 'actioned' end;
  update public.claim_disputes
  set status = v_status, resolved_by = auth.uid(), resolved_at = now()
  where id = v_row.id;
  return jsonb_build_object('id', v_row.id, 'place_id', v_row.place_id, 'status', v_status);
end;
$$;

create function public.official_at(p_lat double precision, p_lng double precision)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_point extensions.geography;
  v_place uuid;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  v_point := private.make_point(p_lat, p_lng);
  select pl.id into v_place
  from public.places pl
  where private.active_claim(pl.id) is not null
    and extensions.st_dwithin(pl.geog, v_point, 80)
  order by extensions.st_distance(pl.geog, v_point)
  limit 1;
  if v_place is null then
    return null;
  end if;
  return private.official_json(v_place, v_uid);
end;
$$;

create function public.official_for_drop(p_drop_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_drop public.drops;
  v_place uuid;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  select * into v_drop from public.drops where id = p_drop_id;
  if not found then
    return null;
  end if;
  v_place := v_drop.place_id;
  if v_place is null or private.active_claim(v_place) is null then
    select pl.id into v_place
    from public.places pl
    where private.active_claim(pl.id) is not null
      and extensions.st_dwithin(pl.geog, v_drop.geog, 80)
    order by extensions.st_distance(pl.geog, v_drop.geog)
    limit 1;
  end if;
  if v_place is null then
    return null;
  end if;
  return private.official_json(v_place, v_uid);
end;
$$;

create function public.my_places()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_profile public.profiles;
begin
  v_profile := private.signed_in_profile();
  return coalesce((
    select jsonb_agg(jsonb_build_object(
      'place_id', pl.id,
      'name', pl.name,
      'role', m.role,
      'status', m.status,
      'plan_slug', t.plan_slug,
      'claim_status', cl.status
    ) order by pl.name)
    from public.place_team_members m
    join public.place_teams t on t.id = m.team_id
    join public.places pl on pl.id = t.place_id
    left join public.place_claims cl on cl.team_id = t.id
    where m.user_id = v_profile.id and m.status in ('invited', 'pending', 'active')
      and t.status in ('forming', 'active')
  ), '[]'::jsonb);
end;
$$;

-- ---------------------------------------------------------------------------
-- Time capsules. The story is stored in private.capsule_secrets, not on the drop.
-- ---------------------------------------------------------------------------
create function public.create_capsule(
  p_title text,
  p_body text default null,
  p_lat double precision default null,
  p_lng double precision default null,
  p_mode text default null,
  p_unlock_at timestamptz default null,
  p_oral_history boolean default false,
  p_media jsonb default '[]'::jsonb,
  p_integrity jsonb default null
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_profile public.profiles;
  v_category public.categories;
  v_limits public.tier_limits;
  v_point extensions.geography;
  v_title text := btrim(coalesce(p_title, ''));
  v_body text := nullif(btrim(coalesce(p_body, '')), '');
  v_media jsonb;
  v_mode text := lower(btrim(coalesce(p_mode, '')));
  v_decision jsonb;
  v_credit boolean;
  v_last_hour integer;
  v_last_day integer;
  v_id uuid;
  v_short text;
begin
  v_profile := private.signed_in_profile();
  if v_mode not in ('date', 'visit', 'both') then
    raise exception 'invalid_capsule' using errcode = '22023', detail = 'mode is date, visit, or both';
  end if;
  if v_mode = 'visit' and p_unlock_at is not null then
    raise exception 'invalid_capsule' using errcode = '22023', detail = 'a visit capsule has no date';
  end if;
  if v_mode in ('date', 'both') and p_unlock_at is null then
    raise exception 'invalid_capsule' using errcode = '22023', detail = 'this capsule needs a date';
  end if;
  if char_length(v_title) not between 1 and 120 then
    raise exception 'invalid_title' using errcode = '22023';
  end if;
  if v_body is not null and char_length(v_body) > 2000 then
    raise exception 'invalid_body' using errcode = '22023';
  end if;
  v_media := private.validate_capsule_media(coalesce(p_media, '[]'::jsonb), v_profile.id);
  if v_body is null and jsonb_array_length(v_media) = 0 then
    raise exception 'invalid_body' using errcode = '22023', detail = 'add a story, a photo, or a voice clip';
  end if;
  perform private.assert_content_allowed('{contact_info}'::text[], array[v_title, v_body]);
  select * into v_category from public.categories where slug = 'time_capsule' and active;
  if not found then
    raise exception 'category_not_found' using errcode = '22023', detail = 'time_capsule';
  end if;
  v_point := private.make_point(p_lat, p_lng);
  perform pg_advisory_xact_lock(hashtextextended('xhere.create_drop:' || v_profile.id::text, 0));
  select * into v_limits from public.tier_limits where tier = v_profile.tier;
  select count(*) filter (where d.created_at > now() - interval '1 hour'), count(*)
    into v_last_hour, v_last_day
  from public.drops d
  where d.author_id = v_profile.id and d.created_at > now() - interval '1 day';
  if v_last_hour >= coalesce(v_limits.drops_per_hour, 0) or v_last_day >= coalesce(v_limits.drops_per_day, 0) then
    raise exception 'rate_limited' using errcode = 'PT429';
  end if;
  v_decision := private.location_decision(v_profile.id, 'create_drop', v_point, p_integrity);
  v_credit := coalesce((v_decision ->> 'credit')::boolean, true);
  v_id := gen_random_uuid();
  if not v_credit then
    perform private.record_location_flag(v_profile.id, 'create_drop', v_id, v_decision -> 'reasons');
  end if;
  insert into public.drops (
    id, author_id, category_id, category_version, source, title, body, attributes, tags,
    geog, display_geog, reveal_radius_m, status,
    location_trust, counts_for_credit,
    capsule_mode, capsule_unlock_at, oral_history
  ) values (
    v_id, v_profile.id, v_category.id, v_category.version, 'user', v_title, null, '{}'::jsonb, '{}',
    v_point, private.fuzz_point(v_point, v_category.location_precision_m),
    v_category.default_reveal_m, 'active',
    case when v_credit then 'standard' else 'reduced' end, v_credit,
    v_mode, case when v_mode = 'visit' then null else p_unlock_at end, coalesce(p_oral_history, false)
  )
  returning short_id into v_short;
  insert into private.capsule_secrets (drop_id, body, media) values (v_id, v_body, v_media);
  return jsonb_build_object(
    'id', v_id,
    'short_id', v_short,
    'capsule_mode', v_mode,
    'capsule_locked', true,
    'capsule_unlock_at', case when v_mode = 'visit' then null else p_unlock_at end,
    'oral_history', coalesce(p_oral_history, false)
  );
end;
$$;

create function public.open_capsules_here(
  p_lat double precision,
  p_lng double precision,
  p_integrity jsonb default null
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_profile public.profiles;
  v_point extensions.geography;
  v_decision jsonb;
  v_credit boolean;
  v_id uuid;
  v_opened integer := 0;
begin
  v_profile := private.signed_in_profile();
  v_point := private.make_point(p_lat, p_lng);
  v_decision := private.location_decision(v_profile.id, 'open_capsule', v_point, p_integrity);
  v_credit := coalesce((v_decision ->> 'credit')::boolean, true);
  for v_id in
    select d.id
    from public.drops d
    where d.capsule_mode is not null
      and d.capsule_opened_at is null
      and extensions.st_dwithin(d.geog, v_point, 30)
  loop
    if private.release_capsule(v_id, v_profile.id, v_point, v_credit, v_decision -> 'reasons') then
      v_opened := v_opened + 1;
    end if;
  end loop;
  return jsonb_build_object('opened', v_opened);
end;
$$;

-- ---------------------------------------------------------------------------
-- Close the new private functions. The map trigger stays callable so an
-- author update still runs it. Nothing else in private is granted.
-- ---------------------------------------------------------------------------
revoke all on function
  private.website_host(text),
  private.free_email_domain(text),
  private.caller_email_domain(),
  private.assert_place_hours(jsonb),
  private.signed_in_profile(),
  private.place_member(uuid, uuid),
  private.active_claim(uuid),
  private.activate_place_claim(uuid, text),
  private.dissolve_place_team(uuid),
  private.clear_official_source(uuid),
  private.official_json(uuid, uuid),
  private.release_capsule(uuid, uuid, extensions.geography, boolean, jsonb),
  private.release_due_capsules(),
  private.validate_capsule_media(jsonb, uuid),
  private.guard_locked_capsule()
from public, anon, authenticated;

grant execute on function private.guard_locked_capsule() to authenticated, service_role;

revoke all on table
  public.place_plans,
  public.places,
  public.place_teams,
  public.place_team_members,
  public.place_claims,
  public.place_official,
  public.claim_disputes,
  public.owner_replies
from public, anon, authenticated;

alter table public.place_plans enable row level security;
alter table public.places enable row level security;
alter table public.place_teams enable row level security;
alter table public.place_team_members enable row level security;
alter table public.place_claims enable row level security;
alter table public.place_official enable row level security;
alter table public.claim_disputes enable row level security;
alter table public.owner_replies enable row level security;

revoke execute on function
  public.create_user_place(text, double precision, double precision, text, jsonb),
  public.start_place_claim(uuid, text, double precision, double precision, jsonb),
  public.prove_claim_email(uuid),
  public.request_claim_postcard(uuid),
  public.submit_claim_for_review(uuid, text),
  public.review_place_claim(uuid, boolean, text),
  public.revoke_place_claim(uuid, text),
  public.invite_place_coowner(uuid, text),
  public.accept_place_invite(uuid),
  public.set_place_hours(uuid, jsonb, text),
  public.post_place_announcement(uuid, text, text, timestamptz, double precision, double precision, jsonb),
  public.reply_as_owner(uuid, text),
  public.owner_replies_for_drop(uuid),
  public.dispute_place_claim(uuid, text, text),
  public.claim_review_queue(integer),
  public.resolve_claim_dispute(uuid, boolean),
  public.official_at(double precision, double precision),
  public.official_for_drop(uuid),
  public.my_places(),
  public.create_capsule(text, text, double precision, double precision, text, timestamptz, boolean, jsonb, jsonb),
  public.open_capsules_here(double precision, double precision, jsonb),
  public.drops_in_view(double precision, double precision, double precision, double precision, text[], integer),
  public.my_impact_notices(integer),
  public.drop_detail(uuid, double precision, double precision, jsonb)
from public, anon, authenticated;

grant execute on function
  public.create_user_place(text, double precision, double precision, text, jsonb),
  public.start_place_claim(uuid, text, double precision, double precision, jsonb),
  public.prove_claim_email(uuid),
  public.request_claim_postcard(uuid),
  public.submit_claim_for_review(uuid, text),
  public.review_place_claim(uuid, boolean, text),
  public.revoke_place_claim(uuid, text),
  public.invite_place_coowner(uuid, text),
  public.accept_place_invite(uuid),
  public.set_place_hours(uuid, jsonb, text),
  public.post_place_announcement(uuid, text, text, timestamptz, double precision, double precision, jsonb),
  public.reply_as_owner(uuid, text),
  public.owner_replies_for_drop(uuid),
  public.dispute_place_claim(uuid, text, text),
  public.claim_review_queue(integer),
  public.resolve_claim_dispute(uuid, boolean),
  public.official_at(double precision, double precision),
  public.official_for_drop(uuid),
  public.my_places(),
  public.create_capsule(text, text, double precision, double precision, text, timestamptz, boolean, jsonb, jsonb),
  public.open_capsules_here(double precision, double precision, jsonb),
  public.my_impact_notices(integer),
  public.drop_detail(uuid, double precision, double precision, jsonb)
to authenticated, service_role;

grant execute on function public.drops_in_view(
  double precision, double precision, double precision, double precision, text[], integer
) to anon, authenticated, service_role;

select cron.schedule(
  'xhere-open-capsules',
  '*/5 * * * *',
  'select private.release_due_capsules()'
);
