-- Reports and the moderator queue learn about contributions.
-- The enum value was added in the previous migration and can be used now.

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
  v_contribution public.contributions;
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
  elsif p_target_type = 'contribution' then
    select * into v_contribution from public.contributions c where c.id = p_target_id;
    if not found or not private.contribution_readable_id(v_contribution.id, v_uid) then
      raise exception 'contribution_not_found' using errcode = 'P0002';
    end if;
    if v_contribution.author_id = v_uid then
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
  elsif p_target_type = 'contribution' and v_contribution.status = 'active' then
    v_threshold := 3;
    if v_contribution.target_kind in ('drop', 'marker', 'open_data') then
      select c.reports_to_hide into v_threshold
      from public.drops d
      join public.categories c on c.id = d.category_id
      left join public.open_data_items i on i.drop_id = d.id
      where (v_contribution.target_kind in ('drop', 'marker') and d.id = v_contribution.target_id)
         or (v_contribution.target_kind = 'open_data' and i.id = v_contribution.target_id);
    end if;
    select p.tier into v_author_tier from public.profiles p where p.id = v_contribution.author_id;
    if v_author_tier >= 'trusted'::public.user_tier then
      v_threshold := coalesce(v_threshold, 3) + (
        select rules.trusted_extra_reports from public.reputation_rules rules where rules.id = 1
      );
    end if;
    select count(distinct r.reporter_id) into v_open_reporters
    from public.reports r
    where r.target_type = 'contribution' and r.target_id = v_contribution.id and r.status = 'open';
    if v_open_reporters >= coalesce(v_threshold, 3) then
      update public.contributions set status = 'pending_review' where id = v_contribution.id;
      insert into public.moderation_actions (actor_id, target_type, target_id, action, reason, metadata)
      values (null, 'contribution', v_contribution.id, 'auto_hide', 'report threshold reached',
              jsonb_build_object('open_reporters', v_open_reporters, 'threshold', v_threshold));
    end if;
  end if;

  return v_report_id;
end;
$$;

create or replace function public.moderate_contribution(p_id uuid, p_action text, p_reason text default null)
returns text
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_row public.contributions;
  v_next text;
begin
  if not private.is_moderator() then
    raise exception 'not_moderator' using errcode = '42501';
  end if;
  select * into v_row from public.contributions c where c.id = p_id;
  if not found then
    raise exception 'contribution_not_found' using errcode = 'P0002';
  end if;
  if v_row.author_id = v_uid then
    raise exception 'cannot_moderate_own_drop' using errcode = '42501';
  end if;
  if p_action = 'approve' then
    if v_row.status <> 'pending_review' and v_row.status <> 'hidden' then
      raise exception 'invalid_transition' using errcode = '22023';
    end if;
    v_next := 'active';
  elsif p_action = 'remove' then
    v_next := 'removed';
  elsif p_action = 'restore' then
    if v_row.status <> 'removed' and v_row.status <> 'hidden' then
      raise exception 'invalid_transition' using errcode = '22023';
    end if;
    v_next := 'active';
  else
    raise exception 'invalid_transition' using errcode = '22023';
  end if;
  update public.contributions set status = v_next where id = p_id;
  insert into public.moderation_actions (actor_id, target_type, target_id, action, reason)
  values (
    v_uid,
    'contribution',
    p_id,
    case p_action when 'approve' then 'approve'::public.moderation_action_type
                  when 'restore' then 'restore'::public.moderation_action_type
                  else 'remove'::public.moderation_action_type end,
    nullif(btrim(p_reason), '')
  );
  update public.reports
  set status = case when p_action = 'remove' then 'actioned'::public.report_status else 'dismissed'::public.report_status end,
      resolved_by = v_uid,
      resolved_at = now()
  where target_type = 'contribution' and target_id = p_id and status = 'open';
  return v_next;
end;
$$;

create or replace function public.moderation_queue(p_limit integer default 50)
returns table (
  kind text,
  target_id uuid,
  short_id text,
  title text,
  body text,
  category_slug text,
  status public.drop_status,
  sensitive boolean,
  subject_id uuid,
  subject_handle text,
  subject_suspended_until timestamptz,
  open_reports integer,
  reasons text[],
  latest_details text,
  report_ids uuid[],
  queued_at timestamptz
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_limit integer := least(greatest(coalesce(p_limit, 50), 1), 200);
begin
  if not private.is_moderator() then
    raise exception 'not_moderator' using errcode = '42501';
  end if;

  return query
  with open_reports as (
    select r.target_type as o_type,
           r.target_id as o_id,
           count(distinct r.reporter_id)::integer as o_count,
           array_agg(distinct r.reason::text) as o_reasons,
           (array_agg(r.details order by r.created_at desc) filter (where r.details is not null))[1] as o_details,
           array_agg(r.id order by r.created_at) as o_ids,
           min(r.created_at) as o_first
    from public.reports r
    where r.status = 'open'
    group by r.target_type, r.target_id
  ),
  items as (
    select 'drop'::text as q_kind,
           d.id as q_target,
           d.short_id as q_short,
           d.title as q_title,
           d.body as q_body,
           c.slug as q_category,
           d.status as q_status,
           c.sensitive as q_sensitive,
           d.author_id as q_subject,
           p.handle as q_handle,
           m.suspended_until as q_suspended,
           coalesce(o.o_count, 0) as q_reports,
           coalesce(o.o_reasons, '{}'::text[]) as q_reasons,
           o.o_details as q_details,
           coalesce(o.o_ids, '{}'::uuid[]) as q_report_ids,
           coalesce(o.o_first, d.updated_at) as q_at
    from public.drops d
    join public.categories c on c.id = d.category_id
    left join public.profiles p on p.id = d.author_id
    left join public.user_moderation m on m.user_id = d.author_id
    left join open_reports o on o.o_type = 'drop' and o.o_id = d.id
    where d.status in ('active', 'confirmed', 'pending_review', 'disputed')
      and (d.status = 'pending_review' or o.o_id is not null)
      and d.author_id is distinct from v_uid
      and (d.author_id is null or p.deleted_at is null)
    union all
    select 'profile'::text,
           p.id,
           null::text,
           null::text,
           null::text,
           null::text,
           null::public.drop_status,
           false,
           p.id,
           p.handle,
           m.suspended_until,
           o.o_count,
           o.o_reasons,
           o.o_details,
           o.o_ids,
           o.o_first
    from open_reports o
    join public.profiles p on p.id = o.o_id
    left join public.user_moderation m on m.user_id = p.id
    where o.o_type = 'profile'
      and p.id <> v_uid
      and p.deleted_at is null
    union all
    select 'contribution'::text,
           c.id,
           null::text,
           left(coalesce(c.body, c.kind), 80),
           c.body,
           c.target_kind,
           case
             when c.status = 'removed' then 'removed'::public.drop_status
             when c.status = 'pending_review' then 'pending_review'::public.drop_status
             else 'active'::public.drop_status
           end,
           false,
           c.author_id,
           p.handle,
           m.suspended_until,
           coalesce(o.o_count, 0),
           coalesce(o.o_reasons, '{}'::text[]),
           o.o_details,
           coalesce(o.o_ids, '{}'::uuid[]),
           coalesce(o.o_first, c.updated_at)
    from public.contributions c
    left join public.profiles p on p.id = c.author_id
    left join public.user_moderation m on m.user_id = c.author_id
    left join open_reports o on o.o_type = 'contribution' and o.o_id = c.id
    where c.status in ('active', 'pending_review', 'hidden')
      and (c.status in ('pending_review', 'hidden') or o.o_id is not null)
      and c.author_id <> v_uid
      and p.deleted_at is null
  )
  select i.q_kind, i.q_target, i.q_short, i.q_title, i.q_body, i.q_category, i.q_status, i.q_sensitive,
         i.q_subject, i.q_handle, i.q_suspended, i.q_reports, i.q_reasons, i.q_details, i.q_report_ids, i.q_at
  from items i
  order by i.q_sensitive desc, (i.q_status = 'pending_review') desc nulls last, i.q_at asc
  limit v_limit;
end;
$$;
