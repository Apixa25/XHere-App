-- M1: Row Level Security on every table, plus explicit grants.
--
-- Model:
-- * Reads of other people's drops go through RPCs (drops_nearby, drops_in_view,
--   drop_detail, drop_teaser). The raw drops table, which holds the true
--   position, is readable only by its author and by moderators.
-- * Drops, confirmations, and reports are written only through RPCs that
--   validate category rules, distance, and rate limits.
-- * Users edit their own profile and drops through column-level grants, so
--   tier, role, points, status, and counters cannot be changed from a client.
-- * service_role bypasses RLS (Supabase default) for Edge Functions and ops.
--
-- Supabase's default privileges grant ALL on new public tables to anon and
-- authenticated, so every table starts from a clean revoke.

do $$
declare
  t text;
begin
  foreach t in array array[
    'profiles', 'user_moderation', 'user_blocks', 'tier_limits', 'content_filters',
    'category_groups', 'categories', 'category_versions', 'drops', 'drop_media',
    'confirmations', 'reports', 'moderation_actions'
  ] loop
    execute format('alter table public.%I enable row level security', t);
    execute format('revoke all on table public.%I from anon, authenticated', t);
  end loop;
end;
$$;

-- ---------------------------------------------------------------------------
-- profiles
-- ---------------------------------------------------------------------------
grant select on public.profiles to authenticated;
grant update (handle, display_name, avatar_path, home_area) on public.profiles to authenticated;

create policy profiles_select on public.profiles
  for select to authenticated
  using (deleted_at is null or id = auth.uid() or private.is_moderator());

create policy profiles_update_own on public.profiles
  for update to authenticated
  using (id = auth.uid() and deleted_at is null)
  with check (id = auth.uid());

-- ---------------------------------------------------------------------------
-- user_moderation: visible to the person and to moderators; written by RPC.
-- ---------------------------------------------------------------------------
grant select on public.user_moderation to authenticated;

create policy user_moderation_select on public.user_moderation
  for select to authenticated
  using (user_id = auth.uid() or private.is_moderator());

-- ---------------------------------------------------------------------------
-- user_blocks: each person manages only their own block list.
-- ---------------------------------------------------------------------------
grant select, insert, delete on public.user_blocks to authenticated;

create policy user_blocks_select_own on public.user_blocks
  for select to authenticated
  using (blocker_id = auth.uid());

create policy user_blocks_insert_own on public.user_blocks
  for insert to authenticated
  with check (blocker_id = auth.uid());

create policy user_blocks_delete_own on public.user_blocks
  for delete to authenticated
  using (blocker_id = auth.uid());

-- ---------------------------------------------------------------------------
-- Reference data: public read, admin write.
-- ---------------------------------------------------------------------------
grant select on public.tier_limits, public.content_filters, public.category_groups,
  public.categories, public.category_versions to anon, authenticated;
grant insert, update, delete on public.tier_limits, public.content_filters,
  public.category_groups, public.categories to authenticated;

create policy tier_limits_select on public.tier_limits
  for select to anon, authenticated using (true);
create policy tier_limits_admin_write on public.tier_limits
  for all to authenticated using (private.is_admin()) with check (private.is_admin());

create policy content_filters_select on public.content_filters
  for select to anon, authenticated using (true);
create policy content_filters_admin_write on public.content_filters
  for all to authenticated using (private.is_admin()) with check (private.is_admin());

create policy category_groups_select on public.category_groups
  for select to anon, authenticated using (active or private.is_admin());
create policy category_groups_admin_insert on public.category_groups
  for insert to authenticated with check (private.is_admin());
create policy category_groups_admin_update on public.category_groups
  for update to authenticated using (private.is_admin()) with check (private.is_admin());
create policy category_groups_admin_delete on public.category_groups
  for delete to authenticated using (private.is_admin());

create policy categories_select on public.categories
  for select to anon, authenticated using (active or private.is_admin());
create policy categories_admin_insert on public.categories
  for insert to authenticated with check (private.is_admin());
create policy categories_admin_update on public.categories
  for update to authenticated using (private.is_admin()) with check (private.is_admin());
create policy categories_admin_delete on public.categories
  for delete to authenticated using (private.is_admin());

create policy category_versions_select on public.category_versions
  for select to anon, authenticated using (true);

-- ---------------------------------------------------------------------------
-- drops: no direct inserts (create_drop only). Authors read, edit a few
-- columns of, and delete their own drops. Moderators read everything.
-- ---------------------------------------------------------------------------
grant select, delete on public.drops to authenticated;
grant update (title, body, tags, display_anonymous) on public.drops to authenticated;

create policy drops_select_own_or_moderator on public.drops
  for select to authenticated
  using (author_id = auth.uid() or private.is_moderator());

create policy drops_update_own on public.drops
  for update to authenticated
  using (author_id = auth.uid() and status not in ('removed', 'expired'))
  with check (author_id = auth.uid());

create policy drops_delete_own on public.drops
  for delete to authenticated
  using (author_id = auth.uid());

-- ---------------------------------------------------------------------------
-- drop_media: authors attach media refs to their own drops. Other users get
-- media only inside a revealed drop payload from the read RPCs.
-- ---------------------------------------------------------------------------
grant select, delete on public.drop_media to authenticated;
grant insert (drop_id, owner_id, storage_bucket, storage_path, media_kind, mime_type,
  byte_size, width, height, duration_s, sort) on public.drop_media to authenticated;

create policy drop_media_select on public.drop_media
  for select to authenticated
  using (owner_id = auth.uid() or private.is_moderator());

create policy drop_media_insert_own on public.drop_media
  for insert to authenticated
  with check (owner_id = auth.uid() and private.is_drop_author(drop_id));

create policy drop_media_delete_own on public.drop_media
  for delete to authenticated
  using (owner_id = auth.uid());

-- ---------------------------------------------------------------------------
-- confirmations: written by confirm_drop (distance check). People can read
-- and retract their own.
-- ---------------------------------------------------------------------------
grant select, delete on public.confirmations to authenticated;

create policy confirmations_select on public.confirmations
  for select to authenticated
  using (user_id = auth.uid() or private.is_moderator());

create policy confirmations_delete_own on public.confirmations
  for delete to authenticated
  using (user_id = auth.uid());

-- ---------------------------------------------------------------------------
-- reports: written by report(). Reporters see their own; moderators see all.
-- ---------------------------------------------------------------------------
grant select on public.reports to authenticated;

create policy reports_select on public.reports
  for select to authenticated
  using (reporter_id = auth.uid() or private.is_moderator());

-- ---------------------------------------------------------------------------
-- moderation_actions: the audit log. Moderators read; RPCs write.
-- ---------------------------------------------------------------------------
grant select on public.moderation_actions to authenticated;

create policy moderation_actions_select on public.moderation_actions
  for select to authenticated
  using (private.is_moderator());
