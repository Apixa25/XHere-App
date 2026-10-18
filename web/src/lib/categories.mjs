/**
 * Labels and sensitivity for the built-in taxonomy.
 * Sensitive slugs match `categories.sensitive` in
 * supabase/migrations/20261018000600_taxonomy.sql.
 * The web never receives that boolean from drop_teaser, so the slug list
 * is how a share page knows to stay on the teaser.
 */
export const SENSITIVE_SLUGS = new Set(["safety_alert", "incident_report"]);

export const CATEGORIES = {
  historical_fact: ["Historical fact", "History & Stories"],
  story: ["Story", "History & Stories"],
  memorial: ["Memorial", "History & Stories"],
  viewpoint: ["Viewpoint", "Nature & Outdoors"],
  trail_note: ["Trail note", "Nature & Outdoors"],
  wildlife: ["Wildlife sighting", "Nature & Outdoors"],
  water_source: ["Water source", "Nature & Outdoors"],
  food_truck: ["Food truck", "Food & Drink"],
  eatery_tip: ["Eatery tip", "Food & Drink"],
  market: ["Market", "Food & Drink"],
  event: ["Event", "Events"],
  popup: ["Pop-up", "Events"],
  yard_sale: ["Yard sale", "Sales & Free Stuff"],
  for_sale: ["For sale", "Sales & Free Stuff"],
  free_stuff: ["Free stuff", "Sales & Free Stuff"],
  hazard: ["Hazard", "Safety & Alerts"],
  safety_alert: ["Safety alert", "Safety & Alerts"],
  incident_report: ["Incident report", "Safety & Alerts"],
  place_of_worship: ["Place of worship", "Community & Faith"],
  community_space: ["Community space", "Community & Faith"],
  notice_board: ["Notice", "Community & Faith"],
  infrastructure: ["Infrastructure", "Infrastructure & Hidden City"],
  construction: ["Construction", "Infrastructure & Hidden City"],
  underground: ["Underground", "Infrastructure & Hidden City"],
  hidden_gem: ["Hidden gem", "Local Secrets"],
  tip: ["Local tip", "Local Secrets"],
  street_art: ["Street art", "Art & Culture"],
  public_art: ["Public art", "Art & Culture"],
  music_spot: ["Music spot", "Art & Culture"],
  note: ["Note", "Note"],
};

export const GROUPS = [
  ["📜", "History & Stories", "What happened here, and the stories people tell about it."],
  ["🌿", "Nature & Outdoors", "Views, trails, wildlife, and water."],
  ["🍔", "Food & Drink", "Trucks, tips, and markets."],
  ["🎪", "Events", "Things happening at a set time."],
  ["🏷️", "Sales & Free Stuff", "Yard sales, things for sale, and free stuff."],
  ["🚨", "Safety & Alerts", "Hazards and alerts. Incident reports follow stricter rules."],
  ["🏛️", "Community & Faith", "Places of worship, community spaces, and notices."],
  ["🔧", "Infrastructure & Hidden City", "How the city works, what is being built, and what is underneath."],
  ["💎", "Local Secrets", "Revealed when you are standing there."],
  ["🎨", "Art & Culture", "Street art, public art, and music."],
  ["📍", "Note", "Anything else worth knowing about this spot."],
];

export function categoryMeta(slug) {
  const row = CATEGORIES[slug];
  if (!row) {
    return {
      label: slug || "Drop",
      group: "XHere",
      sensitive: false,
    };
  }
  return {
    label: row[0],
    group: row[1],
    sensitive: SENSITIVE_SLUGS.has(slug),
  };
}
