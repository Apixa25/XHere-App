-- Media budgets for the free storage tier (PLAN §1.4, PLAN §8).
--
-- A photo is about 2048 px on the long edge and at most 500 KB.
-- A list thumbnail is a sibling `.thumb.jpg`, about 400 px, at most 500 KB.
-- A video is at most 60 seconds, 720p, and 22 MB.
-- Voice clips stay 30 seconds and 256 KB. Avatars stay 512 KB.
-- Existing rows stay valid because these checks only get wider.
-- The smaller drop-media cap applies to new uploads. Objects already
-- stored stay readable.

do $checks$
declare
  r record;
begin
  if exists (
    select 1
    from pg_constraint con
    join pg_class rel on rel.oid = con.conrelid
    join pg_namespace nsp on nsp.oid = rel.relnamespace
    where nsp.nspname = 'public'
      and rel.relname = 'contribution_media'
      and con.contype = 'c'
      and pg_get_constraintdef(con.oid) like '%23068672%'
  ) then
    return;
  end if;
  for r in
    select con.conname
    from pg_constraint con
    join pg_class rel on rel.oid = con.conrelid
    join pg_namespace nsp on nsp.oid = rel.relnamespace
    where nsp.nspname = 'public'
      and rel.relname = 'contribution_media'
      and con.contype = 'c'
      and (
        pg_get_constraintdef(con.oid) like '%8388608%'
        or pg_get_constraintdef(con.oid) like '%1600%'
        or pg_get_constraintdef(con.oid) like '%duration_s%'
      )
  loop
    execute format('alter table public.contribution_media drop constraint %I', r.conname);
  end loop;
  alter table public.contribution_media
    add constraint contribution_media_byte_size_check
    check (byte_size > 0 and byte_size <= 23068672);
  alter table public.contribution_media
    add constraint contribution_media_width_check
    check (width is null or width between 1 and 2048);
  alter table public.contribution_media
    add constraint contribution_media_height_check
    check (height is null or height between 1 and 2048);
  alter table public.contribution_media
    add constraint contribution_media_duration_s_check
    check (duration_s is null or (duration_s > 0 and duration_s <= 60));
end;
$checks$;

-- The live function is the 7-arg create_contribution from the threads migration.
do $fn$
declare
  v_sql text := pg_get_functiondef('public.create_contribution(text, uuid, text, text, boolean, jsonb, uuid)'::regprocedure);
  v_thumb_old constant text := 'v_thumb := ''c/'' || v_id::text || ''/'' || v_media_id::text || ''.thumb.jpg'';'
    || chr(10) || '      else'
    || chr(10) || '        v_thumb := null;';
  v_thumb_new constant text := 'v_thumb := ''c/'' || v_id::text || ''/'' || v_media_id::text || ''.thumb.jpg'';'
    || chr(10) || '      elsif v_thumb_bytes is not null and v_thumb_bytes between 1 and 512000 then'
    || chr(10) || '        v_thumb := ''c/'' || v_id::text || ''/'' || v_media_id::text || ''.thumb.jpg'';'
    || chr(10) || '      else'
    || chr(10) || '        v_thumb := null;';
begin
  if position('23068672' in v_sql) > 0 then
    return;
  end if;
  if position('v_bytes > 8388608 or v_duration is null or v_duration > 30' in v_sql) = 0 then
    raise exception 'create_contribution video size anchor missing';
  end if;
  v_sql := replace(
    v_sql,
    'v_bytes > 8388608 or v_duration is null or v_duration > 30',
    'v_bytes > 23068672 or v_duration is null or v_duration > 60'
  );
  if position('videos are at most 30 seconds and 8 MB' in v_sql) = 0 then
    raise exception 'create_contribution video detail anchor missing';
  end if;
  v_sql := replace(
    v_sql,
    'videos are at most 30 seconds and 8 MB',
    'videos are at most 60 seconds and 22 MB'
  );
  if position('greatest(v_width, v_height) > 1600' in v_sql) = 0 then
    raise exception 'create_contribution photo edge anchor missing';
  end if;
  v_sql := replace(
    v_sql,
    'greatest(v_width, v_height) > 1600',
    'greatest(v_width, v_height) > 2048'
  );
  if position('photos are at most 1600 px' in v_sql) = 0 then
    raise exception 'create_contribution photo detail anchor missing';
  end if;
  v_sql := replace(
    v_sql,
    'photos are at most 1600 px',
    'photos are at most 2048 px'
  );
  if position(v_thumb_old in v_sql) = 0 then
    raise exception 'create_contribution thumb anchor missing';
  end if;
  execute replace(v_sql, v_thumb_old, v_thumb_new);
end;
$fn$;

comment on function public.create_contribution(text, uuid, text, text, boolean, jsonb, uuid) is
  'Adds a contribution and reserves storage paths. Photos are at most 2048 px and 500 KB. Videos are at most 60 seconds, 720p, and 22 MB. A photo thumbnail is optional so older clients still post.';

update storage.buckets
set file_size_limit = 23068672,
    allowed_mime_types = array['image/jpeg', 'image/png', 'image/webp', 'video/mp4', 'video/webm']
where id = 'contribution-media';

update storage.buckets
set file_size_limit = 524288
where id = 'drop-media';

-- A list thumbnail is `<photo>.thumb.jpg` in the same folder. It is readable
-- exactly when the photo it belongs to is readable.
create or replace function private.can_read_drop_media(p_bucket text, p_name text)
returns boolean
language sql
stable
security definer
set search_path = ''
as $read$
  with viewer as (
    select (select auth.uid()) as uid
  )
  select
    (select v.uid from viewer v) is not null
    and exists (
      select 1
      from public.drop_media m
      join public.drops d on d.id = m.drop_id
      cross join viewer v
      where m.storage_bucket = p_bucket
        and (
          m.storage_path = p_name
          or p_name = regexp_replace(m.storage_path, '\.(jpe?g|png|webp)$', '.thumb.jpg')
        )
        and (
          d.author_id = v.uid
          or (
            d.deleted_at is null
            and d.status in ('active'::public.drop_status, 'confirmed'::public.drop_status)
            and (d.expires_at is null or d.expires_at > now())
            and (d.capsule_mode is null or d.capsule_opened_at is not null)
            and exists (
              select 1 from public.categories c
              where c.id = d.category_id and c.active
            )
            and (
              d.author_id is null
              or exists (
                select 1 from public.profiles p
                where p.id = d.author_id and p.deleted_at is null
              )
            )
            and not private.is_blocked_between(v.uid, d.author_id)
            and not exists (
              select 1 from public.hidden_drops h
              where h.user_id = v.uid and h.drop_id = d.id
            )
          )
        )
    );
$read$;

comment on function private.can_read_drop_media(text, text) is
  'True when the signed-in caller may read this private drop object, including a .thumb.jpg sibling of a readable photo. Public active drops are readable by any signed-in user. Hidden, deleted, and sealed capsule media are the owner only.';
