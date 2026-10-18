-- Impact feedback (research recommendation #2).
--
-- Seen by N is a counter of unique signed-in people who opened the drop
-- (drop_detail), not counting the author. Anonymous opens are not counted:
-- a share-page refresh cannot inflate the number. Confirmations were already
-- one row per user per drop; this migration only tells the author, and only
-- for a "still accurate" verdict.
--
-- "Your drop helped" is one in-app row per drop per UTC day. Extra views and
-- confirms that day add to that row. Nothing here sends a push.
--
-- Imported public-record drops (source other than user, author_id null) can
-- be viewed and confirmed the same way. With no author, the counters move
-- and no inbox row is written.
--
-- view_count written before this migration counted every drop_detail call.
-- Those numbers stay. New increments are unique people.

-- ---------------------------------------------------------------------------
-- Who has opened a drop. The row is the dedupe key. Clients cannot read it,
-- so the app never learns who the viewers were.
-- ---------------------------------------------------------------------------
create table public.drop_views (
  drop_id uuid not null references public.drops (id) on delete cascade,
  viewer_id uuid not null references public.profiles (id) on delete cascade,
  first_seen_at timestamptz not null default now(),
  primary key (drop_id, viewer_id)
);
create index drop_views_viewer_idx on public.drop_views (viewer_id);
comment on table public.drop_views is
  'One signed-in viewer per drop. Not readable by clients. Anonymous opens are not stored.';

-- ---------------------------------------------------------------------------
-- Inbox. Counters only: never a viewer id. At most one row per author, drop,
-- and UTC day.
-- ---------------------------------------------------------------------------
create table public.impact_notices (
  id uuid primary key default gen_random_uuid(),
  recipient_id uuid not null references public.profiles (id) on delete cascade,
  drop_id uuid not null references public.drops (id) on delete cascade,
  notice_day date not null,
  view_events integer not null default 0 check (view_events >= 0),
  confirm_events integer not null default 0 check (confirm_events >= 0),
  read_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (recipient_id, drop_id, notice_day),
  check (view_events + confirm_events > 0)
);
create index impact_notices_inbox_idx on public.impact_notices (recipient_id, updated_at desc);
create index impact_notices_drop_idx on public.impact_notices (drop_id);
comment on table public.impact_notices is
  'In-app "your drop helped" notes. One row per drop per UTC day. No push.';

create trigger impact_notices_set_updated_at before update on public.impact_notices
  for each row execute function private.set_updated_at();

comment on column public.drops.view_count is
  'Unique signed-in viewers, excluding the author. Anonymous opens are not counted. Values from before impact feedback counted every drop_detail call.';

-- ---------------------------------------------------------------------------
-- The card already carries confirm_count. Add the cheap view counter and
-- whether the caller already answered, so the Confirm button can show it.
-- ---------------------------------------------------------------------------
alter type public.drop_card add attribute view_count bigint;
alter type public.drop_card add attribute my_verdict public.confirmation_verdict;

-- ---------------------------------------------------------------------------
-- Helpers. private is not exposed by PostgREST. search_path is empty.
-- ---------------------------------------------------------------------------
create function private.note_drop_helped(
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
  -- Imported rows with no author still count. They just have nobody to tell.
  if p_author is null or p_kind not in ('view', 'confirm') then
    return;
  end if;

  insert into public.impact_notices as n (
    recipient_id, drop_id, notice_day, view_events, confirm_events
  ) values (
    p_author,
    p_drop_id,
    (timezone('utc', now()))::date,
    case when p_kind = 'view' then 1 else 0 end,
    case when p_kind = 'confirm' then 1 else 0 end
  )
  on conflict (recipient_id, drop_id, notice_day) do update
    set view_events = n.view_events + excluded.view_events,
        confirm_events = n.confirm_events + excluded.confirm_events;
end;
$$;

-- First open by this signed-in person increments view_count and, when the
-- drop has an author, batches an inbox note. Repeat opens and the author's
-- own opens return the current counter and write nothing.
create function private.record_drop_view(p_drop_id uuid, p_author uuid)
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
  return coalesce(v_count, 0);
end;
$$;

-- A new "still accurate" answer (or a change from "not anymore" to it) is
-- one confirm event. Saying it again, or denying, does not add another.
create function private.confirmations_note_helped()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_author uuid;
begin
  if new.verdict = 'still_true'
     and (tg_op = 'INSERT' or old.verdict is distinct from 'still_true') then
    select d.author_id into v_author from public.drops d where d.id = new.drop_id;
    perform private.note_drop_helped(v_author, new.drop_id, 'confirm');
  end if;
  return null;
end;
$$;

create trigger confirmations_note_helped
  after insert or update of verdict on public.confirmations
  for each row execute function private.confirmations_note_helped();

-- ---------------------------------------------------------------------------
-- Read card: same reveal rules, plus the two counters and the caller's vote.
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
  v_card.is_mine := p_viewer is not null and p_drop.author_id is not distinct from p_viewer;

  if p_viewer is not null then
    select c.verdict into v_card.my_verdict
    from public.confirmations c
    where c.drop_id = p_drop.id and c.user_id = p_viewer;
  end if;

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

-- drop_detail counts a unique view. drop_teaser (the share page) does not:
-- anonymous refreshes must not move the counter.
create or replace function public.drop_detail(
  p_drop_id uuid,
  p_lat double precision default null,
  p_lng double precision default null
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
begin
  select * into v_drop from public.drops d where d.id = p_drop_id;
  if not found or not private.can_view_drop(v_drop, v_uid) then
    raise exception 'drop_not_found' using errcode = 'P0002';
  end if;

  if p_lat is not null or p_lng is not null then
    v_point := private.make_point(p_lat, p_lng);
  end if;

  v_drop.view_count := private.record_drop_view(v_drop.id, v_drop.author_id);
  return private.to_drop_card(v_drop, v_uid, v_point);
end;
$$;

-- ---------------------------------------------------------------------------
-- Inbox RPCs. The tables stay closed; these are the only client path.
-- ---------------------------------------------------------------------------
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
  created_at timestamptz
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
         n.view_events, n.confirm_events, n.read_at, n.created_at
  from public.impact_notices n
  join public.drops d on d.id = n.drop_id
  where n.recipient_id = v_uid
  order by (n.read_at is null) desc, n.updated_at desc
  limit v_limit;
end;
$$;

-- p_notice_id null marks every unread note. A note that is not yours is a no-op.
create function public.mark_impact_notice_read(p_notice_id uuid default null)
returns integer
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_count bigint;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;

  update public.impact_notices n
  set read_at = now()
  where n.recipient_id = v_uid
    and n.read_at is null
    and (p_notice_id is null or n.id = p_notice_id);
  get diagnostics v_count = row_count;
  return v_count::integer;
end;
$$;

-- ---------------------------------------------------------------------------
-- RLS. No client policy: security-definer functions own the writes and reads.
-- (select auth.uid()) is not needed because no policy expression calls it.
-- ---------------------------------------------------------------------------
alter table public.drop_views enable row level security;
alter table public.impact_notices enable row level security;
revoke all on table public.drop_views from anon, authenticated;
revoke all on table public.impact_notices from anon, authenticated;

revoke execute on function
  private.note_drop_helped(uuid, uuid, text),
  private.record_drop_view(uuid, uuid),
  private.confirmations_note_helped()
from public, anon, authenticated;

revoke execute on function
  public.my_impact_notices(integer),
  public.mark_impact_notice_read(uuid)
from public, anon, authenticated;

grant execute on function
  public.my_impact_notices(integer),
  public.mark_impact_notice_read(uuid)
to authenticated, service_role;
