-- Demo time capsule for the Jamestown pilot. The product is not Jamestown-specific.
-- The author is demo_guide (profiles.is_demo). The story stays in
-- private.capsule_secrets until someone else stands here.
-- Idempotent. A no-op when the demo guide has not been loaded.

insert into public.drops (
  short_id, author_id, category_id, category_version, source, title, body,
  geog, display_geog, reveal_radius_m, status, counts_for_credit, location_trust,
  capsule_mode, oral_history
)
select
  'jtownmemory1',
  'd0000000-0000-4000-8000-000000000001',
  c.id,
  c.version,
  'user',
  'A story from this neighborhood',
  null,
  private.make_point(42.09651, -79.24161),
  private.make_point(42.09651, -79.24161),
  c.default_reveal_m,
  'active',
  true,
  'standard',
  'visit',
  true
from public.categories c
where c.slug = 'time_capsule'
  and exists (
    select 1 from public.profiles p
    where p.id = 'd0000000-0000-4000-8000-000000000001' and p.is_demo
  )
  and not exists (select 1 from public.drops d where d.short_id = 'jtownmemory1');

insert into private.capsule_secrets (drop_id, body, media)
select d.id,
  'My grandmother walked this block to the theater. She said the afternoon sounded like a band rehearsing, and she wanted the next person standing here to remember the neighbors, not only the building.',
  '[]'::jsonb
from public.drops d
where d.short_id = 'jtownmemory1'
  and not exists (select 1 from private.capsule_secrets s where s.drop_id = d.id);
