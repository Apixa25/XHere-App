-- RLS on private.tip_lines. The table is not in the Data API (private is
-- absent from api.schemas) and anon/authenticated have no grants. Enabling
-- RLS keeps the "every table" claim true without a client policy.
--
-- create_drop is security definer, owned by the migration role, which
-- bypasses RLS. The tip-line trigger fires inside that function, so it still
-- sees every area. Do not FORCE ROW LEVEL SECURITY here: a non-bypass role
-- would then see zero areas and refuse every incident report.

alter table private.tip_lines enable row level security;
