-- contribution_cards was a security-definer view (advisor 0010). It filtered
-- rows and omitted author_id, but default privileges also granted
-- anon and authenticated INSERT, UPDATE, and DELETE.
--
-- The app lists posts through contributions_for. The view stays so a
-- select of the same safe columns still works. It now runs as the caller.
-- The privileged read lives in private, which PostgREST does not expose.
-- PLAN §4 allow_anonymous_display: the author is stored, public rows say
-- Anonymous, and contribution_author is still the moderator path.

create function private.contribution_card_rows()
returns table (
  id uuid,
  target_kind text,
  target_id uuid,
  parent_id uuid,
  kind text,
  body text,
  created_at timestamptz,
  edited_at timestamptz,
  up_count integer,
  down_count integer,
  score integer,
  author_handle text,
  anonymous boolean,
  is_mine boolean
)
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  return query
  select c.id,
         c.target_kind,
         c.target_id,
         c.parent_id,
         c.kind,
         c.body,
         c.created_at,
         c.edited_at,
         c.up_count,
         c.down_count,
         c.score,
         case when c.display_anonymous then 'Anonymous' else p.handle end,
         c.display_anonymous,
         (select auth.uid()) is not null and c.author_id = (select auth.uid())
  from public.contributions c
  left join public.profiles p on p.id = c.author_id and not c.display_anonymous
  where private.contribution_readable_id(c.id, (select auth.uid()));
  return;
end;
$$;

comment on function private.contribution_card_rows() is
  'Safe contribution rows. author_id is read here and never returned. Anonymous posts are the word Anonymous.';

revoke all on function private.contribution_card_rows() from public, anon, authenticated;
grant execute on function private.contribution_card_rows() to anon, authenticated, service_role;

-- Recreate so security_invoker is on. CREATE OR REPLACE keeps the old option
-- on some Postgres versions. Default privileges grant ALL again, so the
-- revoke below has to follow the create.
drop view public.contribution_cards;
create view public.contribution_cards
with (security_invoker = true) as
select id,
       target_kind,
       target_id,
       parent_id,
       kind,
       body,
       created_at,
       edited_at,
       up_count,
       down_count,
       score,
       author_handle,
       anonymous,
       is_mine
from private.contribution_card_rows();

comment on view public.contribution_cards is
  'Public rows for a contribution. Runs as the caller. author_id is not a column. Anonymous posts show Anonymous. Moderators use contribution_author.';

revoke all on table public.contribution_cards from public, anon, authenticated, service_role;
grant select on table public.contribution_cards to anon, authenticated, service_role;
