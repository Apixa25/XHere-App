-- M1: rule helpers and triggers that enforce category rules in the database
-- (PLAN §4.3). The shared/ Kotlin module mirrors these for instant UI feedback,
-- but these are the source of truth.

-- ---------------------------------------------------------------------------
-- Role and relationship helpers. security definer so RLS policies can call
-- them without recursing into the profiles policy.
-- ---------------------------------------------------------------------------
create function private.is_moderator()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1 from public.profiles p
    where p.id = auth.uid() and p.role in ('moderator', 'admin') and p.deleted_at is null
  );
$$;

create function private.is_admin()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1 from public.profiles p
    where p.id = auth.uid() and p.role = 'admin' and p.deleted_at is null
  );
$$;

create function private.is_drop_author(p_drop_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (select 1 from public.drops d where d.id = p_drop_id and d.author_id = auth.uid());
$$;

-- A block in either direction hides each person's drops from the other.
create function private.is_blocked_between(p_a uuid, p_b uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select p_a is not null and p_b is not null and exists (
    select 1 from public.user_blocks b
    where (b.blocker_id = p_a and b.blocked_id = p_b)
       or (b.blocker_id = p_b and b.blocked_id = p_a)
  );
$$;

grant execute on function private.is_moderator() to anon, authenticated, service_role;
grant execute on function private.is_admin() to anon, authenticated, service_role;
grant execute on function private.is_drop_author(uuid) to authenticated, service_role;

-- ---------------------------------------------------------------------------
-- Geography helpers
-- ---------------------------------------------------------------------------
create function private.make_point(p_lat double precision, p_lng double precision)
returns extensions.geography
language plpgsql
stable
set search_path = ''
as $$
begin
  if p_lat is null or p_lng is null or p_lat not between -90 and 90 or p_lng not between -180 and 180
     or p_lat = 'NaN'::float8 or p_lng = 'NaN'::float8 then
    raise exception 'invalid_location' using errcode = '22023',
      detail = format('lat=%s lng=%s', p_lat, p_lng);
  end if;
  return extensions.st_setsrid(extensions.st_makepoint(p_lng, p_lat), 4326)::extensions.geography;
end;
$$;

-- Snap a point to the center of a grid cell about p_precision_m on a side.
-- Deterministic (the same cell always maps to the same point), so repeated
-- posts cannot be averaged back to the true spot, and idempotent.
create function private.fuzz_point(p_point extensions.geography, p_precision_m integer)
returns extensions.geography
language plpgsql
immutable
set search_path = ''
as $$
declare
  v_lat double precision;
  v_lng double precision;
  v_lat_step double precision;
  v_lng_step double precision;
begin
  if p_precision_m is null or p_precision_m <= 0 then
    return p_point;
  end if;
  v_lat := extensions.st_y(p_point::extensions.geometry);
  v_lng := extensions.st_x(p_point::extensions.geometry);

  v_lat_step := p_precision_m / 111320.0;
  v_lat := floor(v_lat / v_lat_step) * v_lat_step + v_lat_step / 2;
  v_lat := least(greatest(v_lat, -89.9999), 89.9999);

  v_lng_step := p_precision_m / (111320.0 * greatest(cos(radians(v_lat)), 0.01));
  v_lng := floor(v_lng / v_lng_step) * v_lng_step + v_lng_step / 2;
  if v_lng > 180 then
    v_lng := v_lng - 360;
  end if;

  return extensions.st_setsrid(extensions.st_makepoint(v_lng, v_lat), 4326)::extensions.geography;
end;
$$;

-- ---------------------------------------------------------------------------
-- Category rule helpers
-- ---------------------------------------------------------------------------
create function private.needs_premod(p_category public.categories, p_tier public.user_tier)
returns boolean
language sql
immutable
set search_path = ''
as $$
  select case p_category.moderation_mode
    when 'post' then false
    when 'pre_for_new' then p_tier = 'new'
    when 'pre' then p_category.premod_exempt_tier is null or p_tier < p_category.premod_exempt_tier
  end;
$$;

-- Raises content_not_allowed (detail = filter slug, hint = message for the user).
create function private.assert_content_allowed(p_filters text[], p_texts text[])
returns void
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_filter public.content_filters;
begin
  select f.* into v_filter
  from public.content_filters f
  where f.slug = any (p_filters)
    and exists (select 1 from unnest(p_texts) as t(txt) where t.txt ~* f.pattern)
  order by f.slug
  limit 1;

  if found then
    raise exception 'content_not_allowed' using errcode = '22023',
      detail = v_filter.slug, hint = v_filter.message;
  end if;
end;
$$;

-- Visibility used by every read RPC. Authors see their own live and pending
-- drops; everyone else sees only active/confirmed, unexpired drops from live
-- accounts in active categories, with blocks applied both ways.
create function private.can_view_drop(p_drop public.drops, p_viewer uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select case
    when p_drop.author_id is not null and p_drop.author_id = p_viewer then
      p_drop.status in ('active', 'confirmed', 'pending_review', 'disputed')
      and (p_drop.expires_at is null or p_drop.expires_at > now())
    else
      p_drop.status in ('active', 'confirmed')
      and (p_drop.expires_at is null or p_drop.expires_at > now())
      and exists (select 1 from public.categories c where c.id = p_drop.category_id and c.active)
      and (p_drop.author_id is null or exists (
        select 1 from public.profiles p where p.id = p_drop.author_id and p.deleted_at is null))
      and not private.is_blocked_between(p_viewer, p_drop.author_id)
  end;
$$;

-- ---------------------------------------------------------------------------
-- Drops: author edits go through RLS + column grants; this keeps them honest.
-- ---------------------------------------------------------------------------
create function private.drops_before_update()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_category public.categories;
  v_tier public.user_tier;
  v_text_changed boolean := new.title is distinct from old.title or new.body is distinct from old.body;
begin
  -- View counting alone is not an edit.
  if (to_jsonb(new) - array['view_count', 'updated_at']) is distinct from (to_jsonb(old) - array['view_count', 'updated_at']) then
    new.updated_at := now();
  end if;

  if v_text_changed or new.display_anonymous is distinct from old.display_anonymous then
    select * into v_category from public.categories where id = new.category_id;

    if v_text_changed then
      new.title := btrim(new.title);
      perform private.assert_content_allowed(v_category.content_filters, array[new.title, new.body]);
    end if;

    if new.display_anonymous and not v_category.allow_anonymous_display then
      raise exception 'anonymous_not_allowed' using errcode = '22023', detail = v_category.slug;
    end if;

    -- An author's edit to a reviewed category goes back into the queue.
    if v_text_changed and auth.uid() is not null and auth.uid() = old.author_id
       and old.status in ('active', 'confirmed') then
      select p.tier into v_tier from public.profiles p where p.id = old.author_id;
      if private.needs_premod(v_category, v_tier) then
        new.status := 'pending_review';
      end if;
    end if;
  end if;

  return new;
end;
$$;

create trigger drops_before_update before update on public.drops
  for each row execute function private.drops_before_update();

-- ---------------------------------------------------------------------------
-- Media: cap per drop by the author's tier.
-- ---------------------------------------------------------------------------
create function private.drop_media_before_insert()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_max integer;
  v_author uuid;
begin
  select d.author_id, tl.max_media_per_drop into v_author, v_max
  from public.drops d
  left join public.profiles p on p.id = d.author_id
  left join public.tier_limits tl on tl.tier = p.tier
  where d.id = new.drop_id;

  if v_author is not null and v_author <> new.owner_id then
    raise exception 'not_drop_author' using errcode = '42501';
  end if;

  if (select count(*) from public.drop_media m where m.drop_id = new.drop_id) >= coalesce(v_max, 20) then
    raise exception 'too_many_media' using errcode = '22023', detail = format('max %s', coalesce(v_max, 20));
  end if;
  return new;
end;
$$;

create trigger drop_media_before_insert before insert on public.drop_media
  for each row execute function private.drop_media_before_insert();

-- ---------------------------------------------------------------------------
-- Confirmations: counters and status follow the category's bars.
-- ---------------------------------------------------------------------------
create function private.refresh_drop_confirmations(p_drop_id uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_confirms integer;
  v_denies integer;
begin
  select count(*) filter (where c.verdict = 'still_true'),
         count(*) filter (where c.verdict = 'not_true')
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

create function private.confirmations_after_change()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform private.refresh_drop_confirmations(coalesce(new.drop_id, old.drop_id));
  return null;
end;
$$;

create trigger confirmations_after_change after insert or update or delete on public.confirmations
  for each row execute function private.confirmations_after_change();

create trigger confirmations_set_updated_at before update on public.confirmations
  for each row execute function private.set_updated_at();
