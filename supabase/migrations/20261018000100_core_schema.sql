-- M1: core tables (PLAN §5), enums, indexes, and integrity triggers.
-- Rule helpers: 000200_rules. RLS and grants: 000300_rls. RPCs: 000400_rpcs.
-- pg_cron jobs: 000500_cron. Built-in taxonomy: 000600_taxonomy.

-- ---------------------------------------------------------------------------
-- Enums. Enum order matters: user_tier compares new < regular < trusted < guide.
-- ---------------------------------------------------------------------------
create type public.user_tier as enum ('new', 'regular', 'trusted', 'guide');
create type public.app_role as enum ('user', 'moderator', 'admin');
create type public.drop_status as enum ('active', 'pending_review', 'confirmed', 'disputed', 'expired', 'removed');
create type public.drop_source as enum ('user', 'official', 'open_data', 'business');
create type public.moderation_mode as enum ('post', 'pre', 'pre_for_new');
create type public.confirmation_verdict as enum ('still_true', 'not_true');
create type public.media_kind as enum ('image', 'video', 'audio', 'file');
create type public.report_target as enum ('drop', 'profile');
create type public.report_reason as enum (
  'spam', 'harassment', 'hate', 'violence', 'sexual', 'illegal',
  'personal_info', 'misinformation', 'dangerous', 'other'
);
create type public.report_status as enum ('open', 'actioned', 'dismissed');
create type public.moderation_action_type as enum (
  'approve', 'reject', 'remove', 'restore', 'auto_hide',
  'dismiss_report', 'action_report', 'suspend_user', 'unsuspend_user', 'set_tier'
);

-- ---------------------------------------------------------------------------
-- Generic helpers
-- ---------------------------------------------------------------------------
create function private.set_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

-- 12 url-safe characters from 72 random bits, for xhere.world/d/{short_id}.
create function private.new_short_id()
returns text
language sql
volatile
set search_path = ''
as $$
  select rtrim(translate(encode(
    substring(uuid_send(gen_random_uuid()) from 1 for 6)
      || substring(uuid_send(gen_random_uuid()) from 11 for 3),
    'base64'), '+/', '-_'), '=');
$$;

-- ---------------------------------------------------------------------------
-- People
-- ---------------------------------------------------------------------------
create table public.profiles (
  id uuid primary key references auth.users (id) on delete cascade,
  handle text not null unique check (handle ~ '^[a-z0-9_]{3,24}$'),
  display_name text check (char_length(display_name) between 1 and 50),
  avatar_path text check (char_length(avatar_path) <= 512),
  tier public.user_tier not null default 'new',
  role public.app_role not null default 'user',
  points integer not null default 0,
  home_area text check (char_length(home_area) <= 80),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);
comment on table public.profiles is 'One row per auth user. tier/role/points are server-managed; users may edit handle, display_name, avatar_path, home_area.';

create trigger profiles_set_updated_at before update on public.profiles
  for each row execute function private.set_updated_at();

-- Moderation state for a person. Kept apart from profiles so other users never see it.
create table public.user_moderation (
  user_id uuid primary key references public.profiles (id) on delete cascade,
  suspended_until timestamptz,
  reason text check (char_length(reason) <= 500),
  updated_by uuid references public.profiles (id) on delete set null,
  updated_at timestamptz not null default now()
);

create table public.user_blocks (
  blocker_id uuid not null references public.profiles (id) on delete cascade,
  blocked_id uuid not null references public.profiles (id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (blocker_id, blocked_id),
  check (blocker_id <> blocked_id)
);
create index user_blocks_blocked_idx on public.user_blocks (blocked_id);

-- Every new auth user gets a profile with a neutral generated handle.
create function private.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_handle text := 'user_' || substr(md5(new.id::text), 1, 12);
begin
  while exists (select 1 from public.profiles p where p.handle = v_handle) loop
    v_handle := 'user_' || substr(md5(new.id::text || clock_timestamp()::text), 1, 12);
  end loop;
  insert into public.profiles (id, handle)
  values (new.id, v_handle)
  on conflict (id) do nothing;
  return new;
end;
$$;

create trigger on_auth_user_created after insert on auth.users
  for each row execute function private.handle_new_user();

-- Per-tier limits (rows, not constants, so they can be tuned without a release).
create table public.tier_limits (
  tier public.user_tier primary key,
  drops_per_hour integer not null check (drops_per_hour >= 0),
  drops_per_day integer not null check (drops_per_day >= 0),
  confirmations_per_hour integer not null check (confirmations_per_hour >= 0),
  reports_per_day integer not null check (reports_per_day >= 0),
  max_media_per_drop integer not null check (max_media_per_drop between 0 and 20),
  updated_at timestamptz not null default now()
);

create trigger tier_limits_set_updated_at before update on public.tier_limits
  for each row execute function private.set_updated_at();

-- Text filters that categories opt into (contact info, descriptions of people).
-- Patterns are case-insensitive POSIX regexes matched against title and body.
create table public.content_filters (
  slug text primary key check (slug ~ '^[a-z][a-z0-9_]{1,40}$'),
  description text not null,
  pattern text not null check (('' ~* pattern) is not null),
  message text not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create trigger content_filters_set_updated_at before update on public.content_filters
  for each row execute function private.set_updated_at();

-- ---------------------------------------------------------------------------
-- Taxonomy: groups and categories are data, with per-category rules (PLAN §4)
-- ---------------------------------------------------------------------------
create table public.category_groups (
  id uuid primary key default gen_random_uuid(),
  slug text not null unique check (slug ~ '^[a-z][a-z0-9_]{1,40}$'),
  label text not null check (char_length(label) between 1 and 60),
  description text,
  icon text,
  color text check (color ~ '^#[0-9A-Fa-f]{6}$'),
  sort integer not null default 0,
  active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create trigger category_groups_set_updated_at before update on public.category_groups
  for each row execute function private.set_updated_at();

create table public.categories (
  id uuid primary key default gen_random_uuid(),
  group_id uuid not null references public.category_groups (id) on delete restrict,
  slug text not null unique check (slug ~ '^[a-z][a-z0-9_]{1,40}$'),
  label text not null check (char_length(label) between 1 and 60),
  description text,
  icon text,
  color text check (color ~ '^#[0-9A-Fa-f]{6}$'),

  -- JSON Schema for drops.attributes, validated with pg_jsonschema.
  attributes_schema jsonb not null default '{"type": "object", "additionalProperties": false}'::jsonb
    check (jsonb_typeof(attributes_schema) = 'object' and extensions.jsonschema_is_valid(attributes_schema::json)),

  -- Who can post, and whether posts wait for review.
  min_tier_to_post public.user_tier not null default 'new',
  moderation_mode public.moderation_mode not null default 'post',
  -- With moderation_mode = 'pre', authors at or above this tier skip review. Null: everyone is reviewed.
  premod_exempt_tier public.user_tier,
  sensitive boolean not null default false,
  posting_notice text,
  content_filters text[] not null default '{}',

  -- Proximity reveal and location blurring, in meters.
  default_reveal_m integer not null check (default_reveal_m between 10 and 50000),
  max_reveal_m integer not null check (max_reveal_m between 10 and 50000),
  location_precision_m integer not null default 0 check (location_precision_m between 0 and 5000),

  -- Lifetime. Null default_ttl means permanent. max_ttl caps how far in the
  -- future expires_at may be at any time; max_ttl_new is the cap for New tier.
  default_ttl interval check (default_ttl > interval '0'),
  max_ttl interval check (max_ttl > interval '0'),
  max_ttl_new interval check (max_ttl_new > interval '0'),
  -- When set, expires_at comes from this attribute (e.g. events end at ends_at).
  expires_at_attribute text,
  -- Each "still true" pushes expiry out by this much (food trucks, hazards).
  confirm_extends_ttl interval check (confirm_extends_ttl > interval '0'),
  -- Reaching the confirmation bar makes the drop permanent (the "note" rule).
  permanent_when_confirmed boolean not null default false,

  -- Verification and reporting thresholds.
  confirmations_to_confirm integer not null default 3 check (confirmations_to_confirm >= 1),
  denials_to_hide integer not null default 3 check (denials_to_hide >= 1),
  reports_to_hide integer not null default 3 check (reports_to_hide >= 1),

  requires_photo boolean not null default false,
  allow_anonymous_display boolean not null default true,

  embedding extensions.vector(384),
  is_builtin boolean not null default false,
  active boolean not null default true,
  sort integer not null default 0,
  version integer not null default 1,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),

  check (max_reveal_m >= default_reveal_m),
  check (default_reveal_m >= location_precision_m),
  check (default_ttl is null or max_ttl is null or default_ttl <= max_ttl),
  check (max_ttl_new is null or max_ttl is null or max_ttl_new <= max_ttl),
  check (moderation_mode = 'pre' or premod_exempt_tier is null),
  check (expires_at_attribute is null or attributes_schema -> 'properties' ? expires_at_attribute)
);
create index categories_group_idx on public.categories (group_id, sort);

-- Drops keep the category version they were created with; this keeps each version's rules.
create table public.category_versions (
  category_id uuid not null references public.categories (id) on delete cascade,
  version integer not null,
  snapshot jsonb not null,
  created_at timestamptz not null default now(),
  primary key (category_id, version)
);

create function private.categories_before_write()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  v_missing text;
begin
  select f into v_missing
  from unnest(new.content_filters) as f
  where not exists (select 1 from public.content_filters cf where cf.slug = f)
  limit 1;
  if v_missing is not null then
    raise exception 'unknown_content_filter' using errcode = '22023', detail = v_missing;
  end if;

  if tg_op = 'UPDATE' then
    new.updated_at := now();
    if (to_jsonb(new) - array['updated_at', 'created_at', 'version', 'embedding', 'sort'])
       is distinct from (to_jsonb(old) - array['updated_at', 'created_at', 'version', 'embedding', 'sort']) then
      new.version := old.version + 1;
    else
      new.version := old.version;
    end if;
  end if;
  return new;
end;
$$;

create trigger categories_before_write before insert or update on public.categories
  for each row execute function private.categories_before_write();

create function private.categories_after_write()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if tg_op = 'INSERT' or new.version <> old.version then
    insert into public.category_versions (category_id, version, snapshot)
    values (new.id, new.version, to_jsonb(new) - 'embedding')
    on conflict (category_id, version) do update set snapshot = excluded.snapshot;
  end if;
  return null;
end;
$$;

create trigger categories_after_write after insert or update on public.categories
  for each row execute function private.categories_after_write();

-- ---------------------------------------------------------------------------
-- Drops
-- ---------------------------------------------------------------------------
create table public.drops (
  id uuid primary key default gen_random_uuid(),
  short_id text not null unique default private.new_short_id() check (short_id ~ '^[A-Za-z0-9_-]{12}$'),
  author_id uuid references public.profiles (id) on delete cascade,
  category_id uuid not null references public.categories (id) on delete restrict,
  category_version integer not null,
  source public.drop_source not null default 'user',
  title text not null check (char_length(btrim(title)) between 1 and 120),
  body text check (char_length(body) <= 2000),
  attributes jsonb not null default '{}'::jsonb check (jsonb_typeof(attributes) = 'object'),
  tags text[] not null default '{}' check (
    cardinality(tags) <= 10
    and (cardinality(tags) = 0 or array_to_string(tags, ',') ~ '^[a-z0-9][a-z0-9_-]{0,31}(,[a-z0-9][a-z0-9_-]{0,31})*$')
  ),
  -- True position. Never returned to other users; every public read uses display_geog.
  geog extensions.geography(Point, 4326) not null,
  -- Blurred position for categories with location_precision_m > 0, otherwise equal to geog.
  display_geog extensions.geography(Point, 4326) not null,
  reveal_radius_m integer not null check (reveal_radius_m between 10 and 50000),
  status public.drop_status not null default 'active',
  display_anonymous boolean not null default false,
  expires_at timestamptz,
  confirm_count integer not null default 0 check (confirm_count >= 0),
  deny_count integer not null default 0 check (deny_count >= 0),
  last_confirmed_at timestamptz,
  view_count bigint not null default 0,
  removed_reason text check (char_length(removed_reason) <= 500),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  check (source <> 'user' or author_id is not null),
  foreign key (category_id, category_version) references public.category_versions (category_id, version)
);

create index drops_display_geog_idx on public.drops using gist (display_geog);
create index drops_geog_idx on public.drops using gist (geog);
create index drops_category_status_idx on public.drops (category_id, status);
create index drops_expires_idx on public.drops (expires_at) where expires_at is not null;
create index drops_author_created_idx on public.drops (author_id, created_at desc);
create index drops_status_created_idx on public.drops (status, created_at);
create index drops_tags_idx on public.drops using gin (tags);
create index drops_text_trgm_idx on public.drops
  using gin ((coalesce(title, '') || ' ' || coalesce(body, '')) extensions.gin_trgm_ops);

-- Media refs. Files live in Supabase Storage under `{owner_id}/...`; rows only point at them.
create table public.drop_media (
  id uuid primary key default gen_random_uuid(),
  drop_id uuid not null references public.drops (id) on delete cascade,
  owner_id uuid not null references public.profiles (id) on delete cascade,
  storage_bucket text not null default 'drop-media' check (storage_bucket ~ '^[a-z0-9_-]{3,63}$'),
  storage_path text not null check (
    char_length(storage_path) <= 512
    and storage_path ~ '^[A-Za-z0-9/_.-]+$'
    and storage_path !~ '\.\.'
    and split_part(storage_path, '/', 1) = owner_id::text
  ),
  media_kind public.media_kind not null,
  mime_type text not null check (mime_type ~ '^[a-z]+/[a-z0-9.+-]+$'),
  byte_size bigint check (byte_size > 0),
  width integer check (width > 0),
  height integer check (height > 0),
  duration_s numeric(10, 3) check (duration_s >= 0),
  -- Set by the server after EXIF/location checks (M12), never by clients.
  taken_here boolean not null default false,
  sort smallint not null default 0,
  created_at timestamptz not null default now(),
  unique (storage_bucket, storage_path),
  check (
    media_kind = 'file'
    or split_part(mime_type, '/', 1) = media_kind::text
  )
);
create index drop_media_drop_idx on public.drop_media (drop_id, sort);

-- "Still true?" One row per user per drop (fixes the old JSONB voters list).
create table public.confirmations (
  drop_id uuid not null references public.drops (id) on delete cascade,
  user_id uuid not null references public.profiles (id) on delete cascade,
  verdict public.confirmation_verdict not null,
  note text check (char_length(note) <= 280),
  -- Only the distance is kept; the caller's position is not stored.
  distance_m integer not null check (distance_m >= 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (drop_id, user_id)
);
create index confirmations_user_idx on public.confirmations (user_id, updated_at desc);

-- ---------------------------------------------------------------------------
-- Safety: reports and the moderation audit log
-- ---------------------------------------------------------------------------
create table public.reports (
  id uuid primary key default gen_random_uuid(),
  target_type public.report_target not null,
  target_id uuid not null,
  reporter_id uuid not null references public.profiles (id) on delete cascade,
  reason public.report_reason not null,
  details text check (char_length(details) <= 1000),
  status public.report_status not null default 'open',
  created_at timestamptz not null default now(),
  resolved_by uuid references public.profiles (id) on delete set null,
  resolved_at timestamptz,
  resolution_note text check (char_length(resolution_note) <= 1000),
  check ((status = 'open') = (resolved_at is null))
);
create unique index reports_one_open_per_reporter_idx
  on public.reports (reporter_id, target_type, target_id) where status = 'open';
create index reports_queue_idx on public.reports (status, created_at);
create index reports_target_idx on public.reports (target_type, target_id);
create index reports_reporter_created_idx on public.reports (reporter_id, created_at desc);

create table public.moderation_actions (
  id bigint generated always as identity primary key,
  -- Null actor means the system (auto-hide, cron).
  actor_id uuid references public.profiles (id) on delete set null,
  target_type text not null check (target_type in ('drop', 'profile', 'report', 'category')),
  target_id uuid not null,
  action public.moderation_action_type not null,
  reason text check (char_length(reason) <= 1000),
  metadata jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);
create index moderation_actions_target_idx on public.moderation_actions (target_type, target_id, created_at desc);
