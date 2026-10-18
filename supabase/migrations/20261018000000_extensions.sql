-- M1: extensions and the private helper schema.
-- PostGIS, pg_trgm, pg_jsonschema, and vector live in `extensions` (Supabase
-- convention). pg_cron installs into pg_catalog and creates the `cron` schema.

create extension if not exists postgis with schema extensions;
create extension if not exists pg_trgm with schema extensions;
create extension if not exists pg_jsonschema with schema extensions;
create extension if not exists vector with schema extensions;
create extension if not exists pg_cron;

-- `private` holds trigger functions, rule helpers, and cron jobs. It is not in
-- the PostgREST `api.schemas` list, so nothing here is callable over HTTP.
-- RLS policies call a few helpers, so API roles get USAGE plus EXECUTE on
-- exactly those functions. Postgres grants EXECUTE to PUBLIC on every new
-- function and a per-schema default cannot take that away, so
-- 20261018000500_cron.sql revokes it from everything in private.
create schema if not exists private;
revoke all on schema private from public;
grant usage on schema private to anon, authenticated, service_role;

-- Supabase grants every new public function to anon and authenticated by
-- default. Each RPC is revoked and re-granted explicitly; this keeps future
-- public functions closed to those roles until someone grants them on purpose.
alter default privileges in schema public revoke execute on functions from anon, authenticated;
