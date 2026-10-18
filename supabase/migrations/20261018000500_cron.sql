-- M1: scheduled jobs (pg_cron). These replace the old app's 60-second setInterval.
-- Each job is a plain function in `private`, so tests can call it directly.

-- Expire drops whose lifetime has passed. Read RPCs already hide them by
-- expires_at; this makes the status match.
create function private.expire_drops()
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_count integer;
begin
  update public.drops
  set status = 'expired'
  where status in ('active', 'confirmed', 'disputed', 'pending_review')
    and expires_at is not null
    and expires_at <= now();
  get diagnostics v_count = row_count;
  return v_count;
end;
$$;

-- Hard-delete accounts soft-deleted more than p_grace ago. Deleting the auth
-- user cascades to the profile, drops, media refs, confirmations, and reports.
-- Storage objects are removed by an Edge Function once Storage is wired (M5).
create function private.purge_deleted_accounts(p_grace interval default interval '30 days')
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_count integer;
begin
  delete from auth.users u
  using public.profiles p
  where p.id = u.id
    and p.deleted_at is not null
    and p.deleted_at < now() - p_grace;
  get diagnostics v_count = row_count;
  return v_count;
end;
$$;

-- Basic tiers (PLAN §3): promotions only. Guide is granted by hand; demotions
-- are moderator decisions. A "strike" is a drop a moderator removed in the last 90 days.
--   new -> regular: account 7+ days old, 3+ drops not removed, no strikes
--   regular -> trusted: account 30+ days old, 10+ drops that reached their confirmation bar, no strikes
create function private.recompute_tiers()
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_count integer;
begin
  with stats as (
    select p.id,
           p.tier,
           p.created_at,
           count(d.id) filter (where d.status <> 'removed') as live_drops,
           count(d.id) filter (where d.status <> 'removed' and d.confirm_count >= c.confirmations_to_confirm) as confirmed_drops,
           count(d.id) filter (where d.status = 'removed' and d.updated_at > now() - interval '90 days') as strikes
    from public.profiles p
    left join public.drops d on d.author_id = p.id
    left join public.categories c on c.id = d.category_id
    where p.deleted_at is null and p.tier in ('new', 'regular')
    group by p.id
  ),
  promoted as (
    update public.profiles p
    set tier = case s.tier when 'new' then 'regular'::public.user_tier else 'trusted'::public.user_tier end
    from stats s
    where p.id = s.id
      and s.strikes = 0
      and (
        (s.tier = 'new' and s.created_at <= now() - interval '7 days' and s.live_drops >= 3)
        or (s.tier = 'regular' and s.created_at <= now() - interval '30 days' and s.confirmed_drops >= 10)
      )
    returning p.id, s.tier as from_tier, p.tier as to_tier
  )
  insert into public.moderation_actions (actor_id, target_type, target_id, action, reason, metadata)
  select null, 'profile', pr.id, 'set_tier', 'automatic promotion',
         jsonb_build_object('from', pr.from_tier, 'to', pr.to_tier)
  from promoted pr;
  get diagnostics v_count = row_count;
  return v_count;
end;
$$;

-- Close everything in private to PUBLIC (see 20261018000000_extensions.sql),
-- then reopen only the helpers that RLS policies call.
revoke execute on all functions in schema private from public;
grant execute on function private.is_moderator() to anon, authenticated, service_role;
grant execute on function private.is_admin() to anon, authenticated, service_role;
grant execute on function private.is_drop_author(uuid) to authenticated, service_role;

select cron.schedule('xhere-expire-drops', '*/5 * * * *', 'select private.expire_drops()');
select cron.schedule('xhere-purge-deleted-accounts', '17 3 * * *', 'select private.purge_deleted_accounts()');
select cron.schedule('xhere-recompute-tiers', '41 3 * * *', 'select private.recompute_tiers()');
