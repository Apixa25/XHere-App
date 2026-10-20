-- Who may read a private drop photo.
--
-- The phone asks Storage to sign drop-media/<user id>/<file>.jpg. Storage
-- runs that as a SELECT. With no matching policy the object is hidden, and
-- the sign endpoint answers 400 not_found, so the detail screen has no image.
--
-- The bucket stays private. This policy is the allow list:
--   * an active or confirmed drop that is not deleted, not expired, and not
--     a sealed capsule: any signed-in user, except someone who hid it or is
--     blocked from the author
--   * a hidden, deleted, removed, pending, or sealed photo: the owner only
--
-- The helper is security definer so the check can see drop_media. That table's
-- own SELECT policy is owner-only, and a policy that joined it directly would
-- hide every other person's photo. auth.uid() is read once. The lookup uses
-- the unique (storage_bucket, storage_path) index.

create function private.can_read_drop_media(p_bucket text, p_name text)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
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
        and m.storage_path = p_name
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
$$;

comment on function private.can_read_drop_media(text, text) is
  'True when the signed-in caller may read this private drop object. Public active drops are readable by any signed-in user. Hidden, deleted, and sealed capsule media are the owner only.';

revoke all on function private.can_read_drop_media(text, text) from public, anon;
grant execute on function private.can_read_drop_media(text, text) to authenticated;

-- Hosted projects that lost the folder policy still need the owner path.
-- A sealed capsule keeps its file in Storage and its story in
-- private.capsule_secrets, so there is no drop_media row yet. The folder
-- check is what keeps that file owner-only until the capsule opens.
do $$
begin
  if not exists (
    select 1 from pg_policies
    where schemaname = 'storage'
      and tablename = 'objects'
      and policyname = 'drop_media_select_own'
  ) then
    create policy drop_media_select_own on storage.objects
      for select to authenticated
      using (
        bucket_id = 'drop-media'
        and (storage.foldername(name))[1] = (select auth.uid())::text
      );
  end if;
end;
$$;

-- owner_id is what Storage writes on the user-JWT upload. Matching it covers
-- a sign request even when the folder expression does not.
drop policy if exists drop_media_select_owner_id on storage.objects;
create policy drop_media_select_owner_id on storage.objects
  for select to authenticated
  using (
    bucket_id = 'drop-media'
    and owner_id = (select auth.uid())::text
  );

drop policy if exists drop_media_select_visible on storage.objects;
create policy drop_media_select_visible on storage.objects
  for select to authenticated
  using (
    bucket_id = 'drop-media'
    and (select private.can_read_drop_media(bucket_id, name))
  );

comment on policy drop_media_select_visible on storage.objects is
  'Signed-in read of a public active drop photo. Sealed, hidden, and deleted media stay owner-only.';

-- media_grants is revoked from authenticated. A policy that reads that table
-- as the caller makes every Storage SELECT fail with permission denied, and
-- the sign endpoint turns that into the same 400. The grant check therefore
-- runs as the function owner. A grant still cannot outlive a hide, a delete,
-- or a sealed capsule: the visibility helper has to agree.
create function private.caller_has_media_grant(p_name text)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public.media_grants g
    where g.user_id = (select auth.uid())
      and g.storage_path = p_name
      and g.expires_at > now()
  );
$$;

comment on function private.caller_has_media_grant(text) is
  'True when the signed-in caller holds an unexpired media grant for this object path.';

revoke all on function private.caller_has_media_grant(text) from public, anon;
grant execute on function private.caller_has_media_grant(text) to authenticated;

drop policy if exists drop_media_select_granted on storage.objects;
create policy drop_media_select_granted on storage.objects
  for select to authenticated
  using (
    bucket_id = 'drop-media'
    and (select private.caller_has_media_grant(name))
    and (select private.can_read_drop_media(bucket_id, name))
  );
