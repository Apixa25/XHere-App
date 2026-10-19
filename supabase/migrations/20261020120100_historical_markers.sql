-- Historical markers layer.
-- Region-agnostic. A region YAML names the OpenStreetMap source; this migration
-- only adds the category those rows use. HMdb text is not stored. A link may be.

insert into public.categories (
  group_id, slug, label, description, icon, color, attributes_schema,
  min_tier_to_post, moderation_mode, sensitive, posting_notice, content_filters,
  default_reveal_m, max_reveal_m, location_precision_m,
  confirmations_to_confirm, denials_to_hide, reports_to_hide,
  allow_anonymous_display, is_builtin, sort, suggestion_hints
)
select g.id,
       'historical_marker',
       'Historical marker',
       'A plaque, roadside marker, or historic information board.',
       '🪧',
       g.color,
       '{"type":"object","additionalProperties":false,"properties":{
          "era":{"type":"string","maxLength":60},
          "year":{"type":"integer","minimum":-5000,"maximum":2100},
          "source_url":{"type":"string","maxLength":500},
          "citation":{"type":"string","maxLength":300},
          "inscription":{"type":"string","maxLength":1000},
          "marker_kind":{"type":"string","maxLength":40},
          "hmdb_url":{"type":"string","maxLength":500}
        }}'::jsonb,
       'new',
       'post',
       false,
       'OpenStreetMap markers are shared under the Open Database License. HMdb text is not copied.',
       '{}',
       150,
       150,
       0,
       3, 3, 3,
       true,
       true,
       4,
       'historical marker roadside plaque inscription board'
from public.category_groups g
where g.slug = 'history_stories';

update public.categories
set embedding = private.hash_embed(
  replace(slug, '_', ' ') || ' ' || label || ' ' || coalesce(description, '') || ' ' || coalesce(suggestion_hints, '')
)
where slug = 'historical_marker';
