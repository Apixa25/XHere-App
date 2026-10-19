-- Alt text for a drop photo (snap-to-drop).
-- The on-device suggestion fills this in. The person can edit it before posting.
-- Screen readers use it as the image content description. No server model is called.

alter table public.drop_media
  add column alt_text text;

alter table public.drop_media
  add constraint drop_media_alt_text_len
  check (alt_text is null or char_length(alt_text) between 1 and 300);

comment on column public.drop_media.alt_text is
  'Short description of the image for someone who cannot see it. Optional. At most 300 characters.';

grant insert (alt_text) on table public.drop_media to authenticated;

-- create_drop copies alt_text from each media object. Blank becomes null. Longer text is cut to 300.
do $patch$
declare
  v_sql text := pg_get_functiondef(
    'public.create_drop(text, double precision, double precision, text, text, jsonb, text[], integer, timestamptz, boolean, jsonb, boolean, jsonb)'::regprocedure
  );
  v_cols_old constant text := 'byte_size, width, height, duration_s, sort, taken_here';
  v_cols_new constant text := 'byte_size, width, height, duration_s, sort, taken_here, alt_text';
  v_select_old constant text := 'private.photo_taken_here(v_point, e.value, v_credit, p_integrity)';
  v_select_new constant text := 'private.photo_taken_here(v_point, e.value, v_credit, p_integrity),
         nullif(left(btrim(e.value ->> ''alt_text''), 300), '''')';
begin
  if position('e.value ->> ''alt_text''' in v_sql) > 0 then
    return;
  end if;
  if position(v_cols_old in v_sql) = 0 or position(v_select_old in v_sql) = 0 then
    raise exception 'create_drop alt_text anchor missing';
  end if;
  execute replace(replace(v_sql, v_cols_old, v_cols_new), v_select_old, v_select_new);
end;
$patch$;

-- The revealed card includes the alt text with the rest of the photo.
do $card$
declare
  v_sql text := pg_get_functiondef('private.to_drop_card(public.drops, uuid, extensions.geography)'::regprocedure);
  v_old constant text := '''taken_here'', m.taken_here';
  v_new constant text := '''taken_here'', m.taken_here,
          ''alt_text'', m.alt_text';
begin
  if position('''alt_text'', m.alt_text' in v_sql) > 0 then
    return;
  end if;
  if position(v_old in v_sql) = 0 then
    raise exception 'to_drop_card alt_text anchor missing';
  end if;
  execute replace(v_sql, v_old, v_new);
end;
$card$;

-- A person's export of their own photos includes the alt text they saved.
do $export$
declare
  v_sql text := pg_get_functiondef('public.export_my_data()'::regprocedure);
  v_old constant text := '''created_at'', dm.created_at';
  v_new constant text := '''created_at'', dm.created_at,
                ''alt_text'', dm.alt_text';
begin
  if position('''alt_text'', dm.alt_text' in v_sql) > 0 then
    return;
  end if;
  if position(v_old in v_sql) = 0 then
    raise exception 'export_my_data alt_text anchor missing';
  end if;
  execute replace(v_sql, v_old, v_new);
end;
$export$;
