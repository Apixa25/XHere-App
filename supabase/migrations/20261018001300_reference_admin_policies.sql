-- Lint 0006 (multiple_permissive_policies): one SELECT policy on the
-- reference tables.
--
-- tier_limits_admin_write and content_filters_admin_write were FOR ALL, so
-- SELECT ran both the public-read policy and the admin policy. SELECT access
-- does not change: anon and authenticated can still read every row
-- (using true). Writes stay admin-only, split the same way category_groups
-- and categories already are. An UPDATE still sees rows because the SELECT
-- policy lets authenticated read them.

drop policy tier_limits_admin_write on public.tier_limits;
create policy tier_limits_admin_insert on public.tier_limits
  for insert to authenticated
  with check (private.is_admin());
create policy tier_limits_admin_update on public.tier_limits
  for update to authenticated
  using (private.is_admin())
  with check (private.is_admin());
create policy tier_limits_admin_delete on public.tier_limits
  for delete to authenticated
  using (private.is_admin());

drop policy content_filters_admin_write on public.content_filters;
create policy content_filters_admin_insert on public.content_filters
  for insert to authenticated
  with check (private.is_admin());
create policy content_filters_admin_update on public.content_filters
  for update to authenticated
  using (private.is_admin())
  with check (private.is_admin());
create policy content_filters_admin_delete on public.content_filters
  for delete to authenticated
  using (private.is_admin());
