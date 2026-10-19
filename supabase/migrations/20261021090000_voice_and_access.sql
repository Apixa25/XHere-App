-- Voice clips and accessibility checks.
--
-- Voice clips are optional audio on a drop: at most 30 seconds and 256 KB,
-- in the private voice-clips bucket. Photos stay in drop-media.
--
-- An accessibility check is three answers (step-free entrance, ramp, accessible
-- restroom) plus an optional note. The map and the Here Card read the majority,
-- the confidence, and the newest answer. A tie is not a majority. Unknown does
-- not vote. Notes stay with the person who wrote them.
--
-- OpenStreetMap wheelchair, toilets:wheelchair, and ramp tags arrive as
-- access_place drops through the open-data importer. This file does not copy
-- a city. Apply the Jamestown seed separately:
--   supabase/seed/open-data/jamestown-ny-access.sql
-- Storage bucket to apply with this migration: voice-clips (private, 262144 bytes).

create type public.access_answer as enum ('yes', 'no', 'unknown');

insert into public.categories (
  group_id, slug, label, description, icon, color, attributes_schema,
  default_reveal_m, max_reveal_m, is_builtin, sort, posting_notice, suggestion_hints
)
select g.id,
       'access_place',
       'Accessibility',
       'Step-free entrance, a ramp, and an accessible restroom.',
       '♿',
       g.color,
       '{"type":"object","additionalProperties":false,"properties":{
          "wheelchair":{"enum":["yes","limited","no","unknown"]},
          "toilets_wheelchair":{"enum":["yes","no","unknown"]},
          "ramp":{"enum":["yes","no","unknown"]},
          "citation":{"type":"string","maxLength":300},
          "source_url":{"type":"string","format":"uri","maxLength":500}
        }}'::jsonb,
       200,
       200,
       true,
       5,
       'Say what you can see. Do not describe people.',
       'step free entrance ramp wheelchair accessible restroom'
from public.category_groups g
where g.slug = 'infrastructure'
  and not exists (select 1 from public.categories c where c.slug = 'access_place');

update public.categories
set embedding = private.hash_embed(
  replace(slug, '_', ' ') || ' ' || label || ' ' || coalesce(description, '') || ' ' || coalesce(suggestion_hints, '')
)
where slug = 'access_place' and embedding is null;

create table public.accessibility_checks (
  id uuid primary key default gen_random_uuid(),
  author_id uuid not null references public.profiles (id) on delete cascade,
  place_key text not null check (place_key ~ '^[A-Za-z0-9:._/-]{3,160}$'),
  drop_id uuid references public.drops (id) on delete set null,
  latitude double precision not null,
  longitude double precision not null,
  geog extensions.geography(Point, 4326) not null,
  step_free public.access_answer not null,
  ramp public.access_answer not null,
  restroom public.access_answer not null,
  notes text check (notes is null or char_length(notes) between 1 and 280),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (author_id, place_key)
);

create index accessibility_checks_geog_idx on public.accessibility_checks using gist (geog);
create index accessibility_checks_place_idx on public.accessibility_checks (place_key);
create index accessibility_checks_drop_idx on public.accessibility_checks (drop_id);

comment on table public.accessibility_checks is
  'One person, one place. The public read is the majority, not this row.';

alter table public.accessibility_checks enable row level security;
revoke all on table public.accessibility_checks from public, anon, authenticated;
grant select on table public.accessibility_checks to authenticated;

create policy accessibility_checks_select_own on public.accessibility_checks
  for select to authenticated
  using (author_id = (select auth.uid()));

-- Majority of yes/no. Unknown is ignored. An even split is not a majority.
create function private.access_field(p_answers public.access_answer[])
returns jsonb
language sql
immutable
set search_path = ''
as $$
  with decisive as (
    select answer from unnest(p_answers) as u(answer) where answer <> 'unknown'
  ),
  counts as (
    select
      count(*) filter (where answer = 'yes') as yes_n,
      count(*) filter (where answer = 'no') as no_n,
      count(*) as n
    from decisive
  )
  select case
    when n = 0 then jsonb_build_object('answer', 'unknown', 'confidence', 0)
    when yes_n = no_n then jsonb_build_object('answer', 'unknown', 'confidence', 0.5)
    when yes_n > no_n then jsonb_build_object('answer', 'yes', 'confidence', yes_n::numeric / n)
    else jsonb_build_object('answer', 'no', 'confidence', no_n::numeric / n)
  end
  from counts;
$$;

create function private.access_status(p_step text, p_ramp text, p_restroom text, p_limited boolean)
returns text
language sql
immutable
set search_path = ''
as $$
  select case
    when p_step = 'unknown' and p_ramp = 'unknown' and p_restroom = 'unknown' then 'unknown'
    when ((p_step = 'yes')::int + (p_ramp = 'yes')::int + (p_restroom = 'yes')::int) > 0
     and ((p_step = 'no')::int + (p_ramp = 'no')::int + (p_restroom = 'no')::int) = 0
     and p_step = 'yes'
     and not coalesce(p_limited, false) then 'accessible'
    when ((p_step = 'no')::int + (p_ramp = 'no')::int + (p_restroom = 'no')::int) > 0
     and ((p_step = 'yes')::int + (p_ramp = 'yes')::int + (p_restroom = 'yes')::int) = 0
     and p_step = 'no' then 'not_accessible'
    else 'partial'
  end;
$$;

create function private.access_summary_for(p_place_key text, p_viewer uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_step jsonb;
  v_ramp jsonb;
  v_rest jsonb;
  v_lat double precision;
  v_lng double precision;
  v_at timestamptz;
  v_n integer;
  v_notes text;
  v_conf numeric;
  v_status text;
  v_title text;
  v_attr jsonb;
  v_limited boolean := false;
  v_answers_step public.access_answer[];
  v_answers_ramp public.access_answer[];
  v_answers_rest public.access_answer[];
begin
  select
    array_agg(step_free),
    array_agg(ramp),
    array_agg(restroom),
    count(*)::integer
  into v_answers_step, v_answers_ramp, v_answers_rest, v_n
  from public.accessibility_checks
  where place_key = p_place_key;

  if coalesce(v_n, 0) > 0 then
    select latitude, longitude, updated_at
      into v_lat, v_lng, v_at
    from public.accessibility_checks
    where place_key = p_place_key
    order by updated_at desc
    limit 1;
    select notes into v_notes
    from public.accessibility_checks
    where place_key = p_place_key and author_id = p_viewer;
    v_title := 'Here';
    v_step := private.access_field(v_answers_step);
    v_ramp := private.access_field(v_answers_ramp);
    v_rest := private.access_field(v_answers_rest);
    v_status := private.access_status(v_step ->> 'answer', v_ramp ->> 'answer', v_rest ->> 'answer', false);
    select avg(value) into v_conf
    from (
      select (v_step ->> 'confidence')::numeric as value where (v_step ->> 'confidence')::numeric > 0
      union all
      select (v_ramp ->> 'confidence')::numeric where (v_ramp ->> 'confidence')::numeric > 0
      union all
      select (v_rest ->> 'confidence')::numeric where (v_rest ->> 'confidence')::numeric > 0
    ) scored;
    return jsonb_build_object(
      'place_key', p_place_key,
      'step_free', v_step ->> 'answer',
      'ramp', v_ramp ->> 'answer',
      'restroom', v_rest ->> 'answer',
      'status', v_status,
      'confidence', coalesce(v_conf, 0),
      'sample_size', v_n,
      'last_confirmed_at', v_at,
      'latitude', v_lat,
      'longitude', v_lng,
      'title', v_title,
      'attribution', 'People here',
      'my_notes', v_notes,
      'limited', false
    );
  end if;

  select d.title, d.attributes,
         extensions.st_y(d.display_geog::extensions.geometry),
         extensions.st_x(d.display_geog::extensions.geometry)
    into v_title, v_attr, v_lat, v_lng
  from public.open_data_items i
  join public.drops d on d.id = i.drop_id
  join public.categories c on c.id = d.category_id
  where i.external_id = p_place_key
    and c.slug = 'access_place'
    and d.status not in ('removed', 'expired')
  limit 1;
  if v_title is null then
    return null;
  end if;
  v_limited := coalesce(v_attr ->> 'wheelchair', '') = 'limited';
  v_step := private.access_field(array[
    case v_attr ->> 'wheelchair'
      when 'yes' then 'yes'::public.access_answer
      when 'limited' then 'yes'::public.access_answer
      when 'no' then 'no'::public.access_answer
      else 'unknown'::public.access_answer
    end
  ]::public.access_answer[]);
  v_ramp := private.access_field(array[
    case v_attr ->> 'ramp'
      when 'yes' then 'yes'::public.access_answer
      when 'no' then 'no'::public.access_answer
      else 'unknown'::public.access_answer
    end
  ]::public.access_answer[]);
  v_rest := private.access_field(array[
    case coalesce(v_attr ->> 'toilets_wheelchair', '')
      when 'yes' then 'yes'::public.access_answer
      when 'no' then 'no'::public.access_answer
      else 'unknown'::public.access_answer
    end
  ]::public.access_answer[]);
  v_status := private.access_status(v_step ->> 'answer', v_ramp ->> 'answer', v_rest ->> 'answer', v_limited);
  select avg(value) into v_conf
  from (
    select (v_step ->> 'confidence')::numeric as value where (v_step ->> 'confidence')::numeric > 0
    union all
    select (v_ramp ->> 'confidence')::numeric where (v_ramp ->> 'confidence')::numeric > 0
    union all
    select (v_rest ->> 'confidence')::numeric where (v_rest ->> 'confidence')::numeric > 0
  ) scored;
  return jsonb_build_object(
    'place_key', p_place_key,
    'step_free', v_step ->> 'answer',
    'ramp', v_ramp ->> 'answer',
    'restroom', v_rest ->> 'answer',
    'status', v_status,
    'confidence', coalesce(v_conf, 0),
    'sample_size', 1,
    'last_confirmed_at', null,
    'latitude', v_lat,
    'longitude', v_lng,
    'title', v_title,
    'attribution', '© OpenStreetMap contributors, ODbL',
    'my_notes', null,
    'limited', v_limited
  );
end;
$$;

create function public.submit_accessibility_check(
  p_place_key text,
  p_lat double precision,
  p_lng double precision,
  p_step_free public.access_answer,
  p_ramp public.access_answer,
  p_restroom public.access_answer,
  p_notes text default null,
  p_drop_id uuid default null
)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v_uid uuid := auth.uid();
  v_notes text := nullif(btrim(coalesce(p_notes, '')), '');
  v_geog extensions.geography;
begin
  if v_uid is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  if p_place_key is null or p_place_key !~ '^[A-Za-z0-9:._/-]{3,160}$' then
    raise exception 'invalid_location' using errcode = '22023', detail = 'place_key';
  end if;
  v_geog := private.make_point(p_lat, p_lng);
  if v_notes is not null and char_length(v_notes) > 280 then
    raise exception 'invalid_note' using errcode = '22023', detail = 'notes';
  end if;
  if p_drop_id is not null and not exists (select 1 from public.drops d where d.id = p_drop_id) then
    raise exception 'drop_not_found' using errcode = 'P0002';
  end if;
  insert into public.accessibility_checks (
    author_id, place_key, drop_id, latitude, longitude, geog,
    step_free, ramp, restroom, notes
  ) values (
    v_uid, p_place_key, p_drop_id, p_lat, p_lng, v_geog,
    p_step_free, p_ramp, p_restroom, v_notes
  )
  on conflict (author_id, place_key) do update
    set drop_id = excluded.drop_id,
        latitude = excluded.latitude,
        longitude = excluded.longitude,
        geog = excluded.geog,
        step_free = excluded.step_free,
        ramp = excluded.ramp,
        restroom = excluded.restroom,
        notes = excluded.notes,
        updated_at = now();
  return private.access_summary_for(p_place_key, v_uid);
end;
$$;

create function public.accessibility_summary(p_place_key text)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select case
    when auth.uid() is null then null
    else coalesce(private.access_summary_for(p_place_key, auth.uid()), 'null'::jsonb)
  end;
$$;

create function public.accessibility_in_view(
  p_min_lng double precision,
  p_min_lat double precision,
  p_max_lng double precision,
  p_max_lat double precision
)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_box extensions.geography;
  v_rows jsonb;
begin
  if auth.uid() is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;
  if p_min_lng >= p_max_lng or p_min_lat >= p_max_lat then
    raise exception 'invalid_bbox' using errcode = '22023';
  end if;
  perform private.make_point(p_min_lat, p_min_lng);
  perform private.make_point(p_max_lat, p_max_lng);
  v_box := extensions.st_makeenvelope(p_min_lng, p_min_lat, p_max_lng, p_max_lat, 4326)::extensions.geography;
  select coalesce(jsonb_agg(summary order by summary ->> 'title'), '[]'::jsonb)
    into v_rows
  from (
    select private.access_summary_for(keys.place_key, auth.uid()) as summary
    from (
      select distinct c.place_key
      from public.accessibility_checks c
      where extensions.st_intersects(c.geog, v_box)
      union
      select i.external_id
      from public.open_data_items i
      join public.drops d on d.id = i.drop_id
      join public.categories cat on cat.id = d.category_id
      where cat.slug = 'access_place'
        and d.status not in ('removed', 'expired')
        and extensions.st_intersects(d.display_geog, v_box)
        and not exists (
          select 1 from public.accessibility_checks c where c.place_key = i.external_id
        )
    ) keys
  ) rolled
  where summary is not null;
  return v_rows;
end;
$$;

revoke all on function
  private.access_field(public.access_answer[]),
  private.access_status(text, text, text, boolean),
  private.access_summary_for(text, uuid),
  public.submit_accessibility_check(text, double precision, double precision, public.access_answer, public.access_answer, public.access_answer, text, uuid),
  public.accessibility_summary(text),
  public.accessibility_in_view(double precision, double precision, double precision, double precision)
from public, anon, authenticated;

grant execute on function
  public.submit_accessibility_check(text, double precision, double precision, public.access_answer, public.access_answer, public.access_answer, text, uuid),
  public.accessibility_summary(text),
  public.accessibility_in_view(double precision, double precision, double precision, double precision)
to authenticated;

-- Audio attached to a drop. 30 seconds, compressed, voice-clips bucket only.
create function private.drop_media_voice_limits()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  -- Older drops may still attach audio in drop-media. The 30 second cap
  -- applies only to clips stored in the voice-clips bucket.
  if new.media_kind <> 'audio' or new.storage_bucket is distinct from 'voice-clips' then
    return new;
  end if;
  if new.mime_type not in ('audio/mp4', 'audio/aac', 'audio/3gpp', 'audio/amr') then
    raise exception 'invalid_media' using errcode = '22023',
      detail = 'audio uses the voice-clips bucket and a compressed type';
  end if;
  if new.duration_s is null or new.duration_s <= 0 or new.duration_s > 30 then
    raise exception 'voice_too_long' using errcode = '22023',
      detail = 'audio is at most 30 seconds';
  end if;
  if new.byte_size is null or new.byte_size > 262144 then
    raise exception 'voice_too_large' using errcode = '22023',
      detail = 'audio is at most 256 KB';
  end if;
  return new;
end;
$$;

create trigger drop_media_voice_limits
  before insert or update on public.drop_media
  for each row execute function private.drop_media_voice_limits();

revoke all on function private.drop_media_voice_limits() from public, anon, authenticated;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
  'voice-clips',
  'voice-clips',
  false,
  262144,
  array['audio/mp4', 'audio/aac', 'audio/3gpp', 'audio/amr']
)
on conflict (id) do update
  set public = excluded.public,
      file_size_limit = excluded.file_size_limit,
      allowed_mime_types = excluded.allowed_mime_types;

create policy voice_clips_select_own on storage.objects
  for select to authenticated
  using (
    bucket_id = 'voice-clips'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  );

create policy voice_clips_insert_own on storage.objects
  for insert to authenticated
  with check (
    bucket_id = 'voice-clips'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  );

create policy voice_clips_update_own on storage.objects
  for update to authenticated
  using (
    bucket_id = 'voice-clips'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  )
  with check (
    bucket_id = 'voice-clips'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  );

create policy voice_clips_delete_own on storage.objects
  for delete to authenticated
  using (
    bucket_id = 'voice-clips'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  );
