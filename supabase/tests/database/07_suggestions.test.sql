-- M5: suggestion log, hash embeddings, and the fixture score for suggest_categories.
begin;
create extension if not exists pgtap with schema extensions;
select plan(19);

select has_column('public', 'categories', 'suggestion_hints', 'categories carry suggestion hints');
-- M7 bumps incident_report to version 2 for the street address filter.
select is((select max(version) from public.categories where slug <> 'incident_report'), 1, 'filling hints does not bump category versions');
select is(
  (select count(*)::integer from public.categories where active and (embedding is null or suggestion_hints is null)),
  0,
  'every active category has hints and a 384-d embedding'
);
select is(extensions.vector_dims(private.hash_embed('food truck')), 384, 'hash embeddings have 384 dimensions');
select ok(
  (private.hash_embed('food truck') operator(extensions.<=>) private.hash_embed('food truck cart'))
    < (private.hash_embed('food truck') operator(extensions.<=>) private.hash_embed('church mosque temple')),
  'a food-truck phrase sits closer to food trucks than to a church'
);
select is(public.embedding_model(), 'hash-v1', 'local embeddings are hash-v1 until gte-small is switched on');
select is(
  (select slug from public.suggest_categories('food truck street food') limit 1),
  'food_truck',
  'a food truck phrase ranks food_truck first'
);
select is(
  (select slug from public.suggest_categories('') limit 1),
  'note',
  'empty text falls back to note'
);
select is(
  (select count(*)::integer from public.suggest_categories('mural on the alley wall')),
  3,
  'the default suggestion list is the top 3'
);
select ok(
  (with s as (
     select score, row_number() over () as n
     from public.suggest_categories('mural on the alley wall')
   )
   select bool_and(a.score >= b.score)
   from s a
   join s b on b.n = a.n + 1),
  'suggestion scores are descending'
);

-- The same 32 phrases as CategoryRankerTest and suggest_test.ts.
create temporary table suggestion_fixtures (phrase text, expected text);
insert into suggestion_fixtures (phrase, expected) values
  ('food truck street food', 'food_truck'),
  ('monument sculpture statue', 'public_art'),
  ('flood on the road', 'hazard'),
  ('farmers market stalls', 'market'),
  ('owl in the oak tree', 'wildlife'),
  ('mural on the alley wall', 'street_art'),
  ('garage sale this weekend', 'yard_sale'),
  ('drinking fountain by the path', 'water_source'),
  ('busker with a guitar', 'music_spot'),
  ('church service on sunday', 'place_of_worship'),
  ('tunnel under the railroad', 'underground'),
  ('construction crane blocking the lane', 'construction'),
  ('hidden courtyard easy to miss', 'hidden_gem'),
  ('museum says this happened in 1890', 'historical_fact'),
  ('popup shop until friday', 'popup'),
  ('restaurant menu what to order', 'eatery_tip'),
  ('muddy trail after the turn', 'trail_note'),
  ('free couch on the curb', 'free_stuff'),
  ('public library community hall', 'community_space'),
  ('scenic overlook of the lake', 'viewpoint'),
  ('theft break-in last night', 'incident_report'),
  ('bridge utility work', 'infrastructure'),
  ('memorial plaque for the town', 'memorial'),
  ('local shortcut only locals know', 'tip'),
  ('community flyer on the notice board', 'notice_board'),
  ('a general note about the benches', 'note'),
  ('personal tale my grandmother told', 'story'),
  ('item for sale pickup here', 'for_sale'),
  ('live festival happening tonight', 'event'),
  ('ice warning sidewalk closed', 'hazard'),
  ('safety alert caution nearby', 'safety_alert'),
  ('taco trailer lunch vehicle', 'food_truck');

do $$
declare
  v_hits integer := 0;
  v_total integer := 0;
  v_row record;
begin
  for v_row in select phrase, expected from suggestion_fixtures loop
    v_total := v_total + 1;
    if exists (
      select 1 from public.suggest_categories(v_row.phrase) s where s.slug = v_row.expected
    ) then
      v_hits := v_hits + 1;
    end if;
  end loop;
  perform set_config('t.hits', v_hits::text, true);
  perform set_config('t.total', v_total::text, true);
  raise notice 'fixture top-3 %/%', v_hits, v_total;
end;
$$;

select ok(
  current_setting('t.hits')::numeric / current_setting('t.total')::numeric >= 0.70,
  format('fixture top-3 %s/%s', current_setting('t.hits'), current_setting('t.total'))
);

select ok(
  not has_function_privilege('anon', 'private.hash_embed(text)', 'execute')
  and not has_function_privilege('anon', 'public.suggest_categories(text,double precision,double precision,integer,text[],integer)', 'execute'),
  'signed-out visitors cannot embed or ask for suggestions'
);
select ok(
  has_function_privilege('authenticated', 'public.suggest_categories(text,double precision,double precision,integer,text[],integer)', 'execute')
  and has_function_privilege('authenticated', 'public.log_category_suggestion(jsonb,text[],text)', 'execute'),
  'signed-in people can suggest and log a choice'
);
select ok(
  not has_table_privilege('authenticated', 'public.category_suggestion_log', 'insert'),
  'suggestion rows are written by the RPC, not by a direct insert'
);

insert into auth.users (id, email) values
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'm5a@example.test'),
  ('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', 'm5b@example.test');

set local role authenticated;
set local request.jwt.claims to '{"sub": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "role": "authenticated"}';
select throws_ok(
  $$ select public.log_category_suggestion('{"text":"x"}'::jsonb, array['note'], 'nope') $$,
  '22023',
  'category_not_found',
  'an unknown chosen category is rejected'
);
select is(
  (public.log_category_suggestion('{"text":"food truck"}'::jsonb, array['food_truck','note','eatery_tip'], 'food_truck') is not null),
  true,
  'a signed-in person can log the suggestion they were shown'
);
select is(
  (select count(*)::integer from public.category_suggestion_log),
  1,
  'the author can read their own suggestion log'
);

set local request.jwt.claims to '{"sub": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "role": "authenticated"}';
select is(
  (select count(*)::integer from public.category_suggestion_log),
  0,
  'someone else cannot read that log'
);

-- storage.buckets is not readable by `authenticated` (no select policy). The
-- migration inserts the row; check it as the table owner.
reset role;
select ok(
  exists (select 1 from storage.buckets where id = 'drop-media' and public = false),
  'drop-media is a private storage bucket'
);

select * from finish();
rollback;
