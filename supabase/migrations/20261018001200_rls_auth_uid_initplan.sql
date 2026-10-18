-- Lint 0003 (auth_rls_initplan): call auth.uid() once per statement.
--
-- A bare auth.uid() in a policy is planned per row. Wrapping it as
-- (select auth.uid()) is an initplan, so Postgres evaluates it once and
-- reuses the value. The comparison is the same: null when nobody is signed
-- in, otherwise the JWT subject. private.is_moderator() and
-- private.is_drop_author() stay as they are; the linter does not flag them.
-- Storage policies already use the wrapped form (20261018000700).

alter policy profiles_select on public.profiles
  using (deleted_at is null or id = (select auth.uid()) or private.is_moderator());

alter policy profiles_update_own on public.profiles
  using (id = (select auth.uid()) and deleted_at is null)
  with check (id = (select auth.uid()));

alter policy user_moderation_select on public.user_moderation
  using (user_id = (select auth.uid()) or private.is_moderator());

alter policy user_blocks_select_own on public.user_blocks
  using (blocker_id = (select auth.uid()));

alter policy user_blocks_insert_own on public.user_blocks
  with check (blocker_id = (select auth.uid()));

alter policy user_blocks_delete_own on public.user_blocks
  using (blocker_id = (select auth.uid()));

alter policy drops_select_own_or_moderator on public.drops
  using (author_id = (select auth.uid()) or private.is_moderator());

alter policy drops_update_own on public.drops
  using (author_id = (select auth.uid()) and status not in ('removed', 'expired'))
  with check (author_id = (select auth.uid()));

alter policy drops_delete_own on public.drops
  using (author_id = (select auth.uid()));

alter policy drop_media_select on public.drop_media
  using (owner_id = (select auth.uid()) or private.is_moderator());

alter policy drop_media_insert_own on public.drop_media
  with check (owner_id = (select auth.uid()) and private.is_drop_author(drop_id));

alter policy drop_media_delete_own on public.drop_media
  using (owner_id = (select auth.uid()));

alter policy confirmations_select on public.confirmations
  using (user_id = (select auth.uid()) or private.is_moderator());

alter policy confirmations_delete_own on public.confirmations
  using (user_id = (select auth.uid()));

alter policy reports_select on public.reports
  using (reporter_id = (select auth.uid()) or private.is_moderator());

alter policy category_suggestion_log_select on public.category_suggestion_log
  using (user_id = (select auth.uid()) or private.is_moderator());

alter policy hidden_drops_select_own on public.hidden_drops
  using (user_id = (select auth.uid()));

alter policy hidden_drops_delete_own on public.hidden_drops
  using (user_id = (select auth.uid()));
