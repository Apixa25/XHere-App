-- M5: category suggestion log, hash embeddings for pgvector, and the drop-media bucket.
--
-- The ranker (public.suggest_categories) blends three signals from PLAN §4.4:
--   * cosine similarity of a 384-d feature hash (hash-v1)
--   * how many visible drops of that category are within 500 m
--   * the poster's recent picks and a small time-of-day prior
-- gte-small is the same shape (vector(384)). The Edge Function uses it only when
-- XHERE_EMBEDDING_MODEL=gte-small, after categories.embedding has been rebuilt
-- with that model. CI and local installs stay on hash-v1 so nothing is downloaded
-- and no Supabase project is required. Mixing the two spaces would make cosine noise.
--
-- suggestion_hints is excluded from the category version bump: it is not a rule.

alter table public.categories add column suggestion_hints text;

create or replace function private.categories_before_write()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  v_missing text;
begin
  select f into v_missing
  from unnest(new.content_filters) as f
  where not exists (select 1 from public.content_filters cf where cf.slug = f)
  limit 1;
  if v_missing is not null then
    raise exception 'unknown_content_filter' using errcode = '22023', detail = v_missing;
  end if;

  if tg_op = 'UPDATE' then
    new.updated_at := now();
    if (to_jsonb(new) - array['updated_at', 'created_at', 'version', 'embedding', 'sort', 'suggestion_hints'])
       is distinct from (to_jsonb(old) - array['updated_at', 'created_at', 'version', 'embedding', 'sort', 'suggestion_hints']) then
      new.version := old.version + 1;
    else
      new.version := old.version;
    end if;
  end if;
  return new;
end;
$$;

-- ---------------------------------------------------------------------------
-- hash-v1. Same tokens, FNV-1a, and L2 norm as HashEmbedding.kt and embed.ts.
-- ---------------------------------------------------------------------------
create function private.hash_embed(p_text text)
returns extensions.vector(384)
language plpgsql
stable
set search_path = ''
as $$
declare
  v_token text;
  v_stem text;
  v_raw bytea;
  v_len integer;
  v_i integer;
  v_hash bigint;
  v_idx integer;
  v_sign integer;
  v_buckets integer[] := array_fill(0, array[384]);
  v_norm float8 := 0;
  v_values text;
begin
  if p_text is null or btrim(p_text) = '' then
    return array_fill(0::float8, array[384])::extensions.vector(384);
  end if;

  for v_token in
    select tok
    from regexp_split_to_table(
      lower(replace(replace(p_text, '_', ' '), '-', ' ')),
      '[^a-z0-9]+'
    ) as tok
  loop
    if char_length(v_token) < 2 then
      continue;
    end if;
    v_stem := v_token;
    if char_length(v_stem) > 4 and right(v_stem, 1) = 's' and right(v_stem, 2) <> 'ss' then
      v_stem := left(v_stem, char_length(v_stem) - 1);
    end if;
    v_raw := convert_to(v_stem, 'UTF8');
    v_len := octet_length(v_raw);
    v_hash := 2166136261;
    v_i := 0;
    while v_i < v_len loop
      v_hash := mod(((v_hash # get_byte(v_raw, v_i)::bigint) * 16777619), 4294967296);
      v_i := v_i + 1;
    end loop;
    v_idx := (v_hash % 384)::integer + 1;
    if ((v_hash >> 9) & 1) = 1 then
      v_sign := 1;
    else
      v_sign := -1;
    end if;
    v_buckets[v_idx] := v_buckets[v_idx] + v_sign;
  end loop;

  v_i := 1;
  while v_i <= 384 loop
    v_norm := v_norm + (v_buckets[v_i]::float8 * v_buckets[v_i]::float8);
    v_i := v_i + 1;
  end loop;
  v_norm := sqrt(v_norm);
  if v_norm = 0 then
    return array_fill(0::float8, array[384])::extensions.vector(384);
  end if;

  select string_agg(trim(to_char(v_buckets[i]::float8 / v_norm, 'FM999990.000000999')), ',' order by i)
    into v_values
  from generate_series(1, 384) as i;
  return ('[' || v_values || ']')::extensions.vector(384);
end;
$$;

revoke execute on function private.hash_embed(text) from public, anon, authenticated;

-- ---------------------------------------------------------------------------
-- Suggestion log. Written only by log_category_suggestion.
-- ---------------------------------------------------------------------------
create table public.category_suggestion_log (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles (id) on delete cascade,
  inputs jsonb not null check (jsonb_typeof(inputs) = 'object'),
  suggested text[] not null check (cardinality(suggested) between 1 and 10),
  chosen_category_id uuid references public.categories (id),
  created_at timestamptz not null default now()
);

create index category_suggestion_log_user_idx
  on public.category_suggestion_log (user_id, created_at desc);

alter table public.category_suggestion_log enable row level security;
revoke all on table public.category_suggestion_log from anon, authenticated;
grant select on public.category_suggestion_log to authenticated;

create policy category_suggestion_log_select on public.category_suggestion_log
  for select to authenticated
  using (user_id = auth.uid() or private.is_moderator());

-- ---------------------------------------------------------------------------
-- Fill hints and embeddings. The version trigger ignores both columns.
-- ---------------------------------------------------------------------------
update public.categories as c
set suggestion_hints = v.hints
from (values
  ('historical_fact', 'history historical museum happened year source archive'),
  ('story', 'story tale personal memory anecdote grandmother'),
  ('memorial', 'memorial plaque remember remembrance candle'),
  ('viewpoint', 'viewpoint view overlook vista scenic lake panorama overlook scenic vista'),
  ('trail_note', 'trail hike path muddy turn conditions hiking'),
  ('wildlife', 'wildlife animal bird owl deer fox plant sighting owl owl'),
  ('water_source', 'drinking fountain spring refill water bottle tap'),
  ('food_truck', 'food truck cart taco street kitchen trailer lunch vehicle'),
  ('eatery_tip', 'restaurant cafe diner menu order dish plate eatery'),
  ('market', 'farmers market stall produce vendor bazaar'),
  ('event', 'event festival concert fair schedule happening show'),
  ('popup', 'popup temporary shop stand kiosk'),
  ('yard_sale', 'yard sale garage sale stoop'),
  ('for_sale', 'for sale item pickup price selling'),
  ('free_stuff', 'free stuff giveaway curb couch freebie'),
  ('hazard', 'hazard flood pothole sidewalk closed ice road warning'),
  ('safety_alert', 'safety alert warning caution danger nearby'),
  ('incident_report', 'incident crime theft break-in robbery police report'),
  ('place_of_worship', 'church mosque temple synagogue worship service faith'),
  ('community_space', 'library community center hall garden public room'),
  ('notice_board', 'notice announcement board flyer community'),
  ('infrastructure', 'bridge utility sewer infrastructure works'),
  ('construction', 'construction crane crew building site roadwork scaffold'),
  ('underground', 'tunnel underground buried railroad underpass subway'),
  ('hidden_gem', 'hidden gem courtyard secret alley easy to miss'),
  ('tip', 'local tip locals know shortcut advice'),
  ('street_art', 'mural graffiti street art wall painting paste'),
  ('public_art', 'monument sculpture statue installation bronze public art'),
  ('music_spot', 'music busker guitar open mic acoustic singer'),
  ('note', 'note miscellaneous other general')
) as v(slug, hints)
where c.slug = v.slug;

update public.categories
set embedding = private.hash_embed(
  replace(slug, '_', ' ') || ' ' || label || ' ' || coalesce(description, '') || ' ' || coalesce(suggestion_hints, '')
)
where active;

create index categories_embedding_idx
  on public.categories
  using hnsw (embedding extensions.vector_cosine_ops);

-- ---------------------------------------------------------------------------
-- Which embedding space categories.embedding is in. The Edge Function reads this
-- before it decides between hash-v1 and gte-small.
-- ---------------------------------------------------------------------------
create function public.embedding_model()
returns text
language sql
stable
security definer
set search_path = ''
as $$
  select 'hash-v1'::text;
$$;

-- Nearest categories for a vector the caller already embedded (the gte-small path).
create function public.match_categories(
  p_embedding extensions.vector(384),
  p_limit integer default 8
)
returns table (slug text, label text, cosine double precision)
language sql
stable
security definer
set search_path = ''
as $$
  select c.slug, c.label,
         (1 - (c.embedding operator(extensions.<=>) p_embedding))::double precision
  from public.categories c
  where c.active and c.embedding is not null
  order by c.embedding operator(extensions.<=>) p_embedding, c.slug
  limit least(greatest(coalesce(p_limit, 8), 1), 30);
$$;

-- Visible drops within 500 m, per category. The Edge Function blends these in.
create function public.nearby_category_counts(
  p_lat double precision,
  p_lng double precision,
  p_radius_m integer default 500
)
returns table (slug text, n integer)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_point extensions.geography;
begin
  if p_lat is null or p_lng is null then
    return;
  end if;
  v_point := private.make_point(p_lat, p_lng);
  return query
  select c.slug, count(*)::integer
  from public.drops d
  join public.categories c on c.id = d.category_id
  where extensions.st_dwithin(d.display_geog, v_point, least(greatest(coalesce(p_radius_m, 500), 1), 5000))
    and private.can_view_drop(d, auth.uid())
  group by c.slug;
end;
$$;

-- Top categories for a line of text plus photo labels, already joined by the caller.
create function public.suggest_categories(
  p_text text,
  p_lat double precision default null,
  p_lng double precision default null,
  p_hour integer default null,
  p_recent text[] default '{}',
  p_limit integer default 3
)
returns table (slug text, label text, score double precision, cosine double precision)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_query extensions.vector(384);
  v_blank boolean;
  v_limit integer := least(greatest(coalesce(p_limit, 3), 1), 10);
begin
  v_query := private.hash_embed(coalesce(p_text, ''));
  select not exists (
    select 1
    from regexp_split_to_table(
      lower(replace(replace(coalesce(p_text, ''), '_', ' '), '-', ' ')),
      '[^a-z0-9]+'
    ) as tok
    where char_length(tok) >= 2
  ) into v_blank;

  return query
  with scored as (
    select c.slug, c.label,
           case when v_blank then 0::float8
                else (1 - (c.embedding operator(extensions.<=>) v_query))::float8
           end as cosine,
           coalesce(n.n, 0) as nearby_n,
           (c.slug = any(coalesce(p_recent, '{}'))) as recent,
           case
             when p_hour is null then 0::float8
             when c.slug in ('food_truck', 'market', 'eatery_tip') and (p_hour between 11 and 14 or p_hour between 17 and 20) then 1::float8
             when c.slug in ('event', 'popup', 'music_spot') and p_hour between 16 and 22 then 1::float8
             when c.slug in ('yard_sale', 'free_stuff', 'for_sale') and p_hour between 8 and 16 then 1::float8
             when c.slug = 'wildlife' and (p_hour between 5 and 9 or p_hour between 17 and 20) then 1::float8
             else 0::float8
           end as time_boost
    from public.categories c
    left join public.nearby_category_counts(p_lat, p_lng, 500) n on n.slug = c.slug
    where c.active and c.embedding is not null
  ),
  best as (
    select coalesce(max(scored.cosine), 0) as best_cosine,
           coalesce(max(scored.nearby_n), 1) as max_nearby
    from scored
  )
  select scored.slug, scored.label,
         (scored.cosine
           + 0.18 * (case when best.max_nearby > 0 then scored.nearby_n::float8 / best.max_nearby else 0 end)
           + 0.12 * (case when scored.recent then 1 else 0 end)
           + 0.08 * scored.time_boost)::double precision as score,
         scored.cosine::double precision
  from scored
  cross join best
  order by
    case when (v_blank or best.best_cosine < 0.02) and scored.slug = 'note' then 0 else 1 end,
    (scored.cosine
      + 0.18 * (case when best.max_nearby > 0 then scored.nearby_n::float8 / best.max_nearby else 0 end)
      + 0.12 * (case when scored.recent then 1 else 0 end)
      + 0.08 * scored.time_boost) desc,
    scored.slug
  limit v_limit;
end;
$$;

create function public.log_category_suggestion(
  p_inputs jsonb,
  p_suggested text[],
  p_chosen text default null
)
returns uuid
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_chosen uuid;
  v_id uuid;
  v_slug text;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  if p_inputs is null or jsonb_typeof(p_inputs) <> 'object' then
    raise exception 'invalid_suggestion' using errcode = '22023', detail = 'inputs must be a JSON object';
  end if;
  if p_suggested is null or cardinality(p_suggested) not between 1 and 10 then
    raise exception 'invalid_suggestion' using errcode = '22023', detail = 'suggested must be 1..10 slugs';
  end if;
  foreach v_slug in array p_suggested loop
    if v_slug !~ '^[a-z][a-z0-9_]{1,40}$' then
      raise exception 'invalid_suggestion' using errcode = '22023', detail = v_slug;
    end if;
  end loop;
  if p_chosen is not null then
    select c.id into v_chosen from public.categories c where c.slug = p_chosen and c.active;
    if v_chosen is null then
      raise exception 'category_not_found' using errcode = '22023', detail = p_chosen;
    end if;
  end if;
  insert into public.category_suggestion_log (user_id, inputs, suggested, chosen_category_id)
  values (v_uid, p_inputs, p_suggested, v_chosen)
  returning id into v_id;
  return v_id;
end;
$$;

revoke execute on function
  public.embedding_model(),
  public.match_categories(extensions.vector(384), integer),
  public.nearby_category_counts(double precision, double precision, integer),
  public.suggest_categories(text, double precision, double precision, integer, text[], integer),
  public.log_category_suggestion(jsonb, text[], text)
from public, anon, authenticated;

grant execute on function
  public.embedding_model(),
  public.match_categories(extensions.vector(384), integer),
  public.nearby_category_counts(double precision, double precision, integer),
  public.suggest_categories(text, double precision, double precision, integer, text[], integer),
  public.log_category_suggestion(jsonb, text[], text)
to authenticated, service_role;

-- Photos live under the author's id. The strip-exif function uploads with the
-- user's JWT, so the folder check is the same one create_drop requires.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
  'drop-media',
  'drop-media',
  false,
  10485760,
  array['image/jpeg', 'image/png', 'image/webp']
)
on conflict (id) do update
  set public = excluded.public,
      file_size_limit = excluded.file_size_limit,
      allowed_mime_types = excluded.allowed_mime_types;

create policy drop_media_select_own on storage.objects
  for select to authenticated
  using (
    bucket_id = 'drop-media'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  );

create policy drop_media_insert_own on storage.objects
  for insert to authenticated
  with check (
    bucket_id = 'drop-media'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  );

create policy drop_media_update_own on storage.objects
  for update to authenticated
  using (
    bucket_id = 'drop-media'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  )
  with check (
    bucket_id = 'drop-media'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  );

create policy drop_media_delete_own on storage.objects
  for delete to authenticated
  using (
    bucket_id = 'drop-media'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  );
