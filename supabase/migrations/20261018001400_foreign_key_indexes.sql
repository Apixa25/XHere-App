-- Lint 0001 (unindexed_foreign_keys): a btree index whose leading columns
-- match each foreign key, so deletes and joins on the parent are not seq scans.
-- drops.category_id is already the leading column of drops_category_status_idx;
-- the composite key (category_id, category_version) is a separate constraint
-- and needs its own index.

create index category_suggestion_log_chosen_category_idx
  on public.category_suggestion_log (chosen_category_id);

create index drop_media_owner_idx
  on public.drop_media (owner_id);

create index drops_category_version_idx
  on public.drops (category_id, category_version);

create index moderation_actions_actor_idx
  on public.moderation_actions (actor_id);

create index reports_resolved_by_idx
  on public.reports (resolved_by);

create index user_moderation_updated_by_idx
  on public.user_moderation (updated_by);
