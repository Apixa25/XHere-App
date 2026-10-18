-- M7: one more audit action. It has its own file because a new enum value
-- cannot be used in the transaction that adds it.
alter type public.moderation_action_type add value if not exists 'set_role';
