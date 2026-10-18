// Label and description match supabase/migrations/20261018000600_taxonomy.sql.
// Hints match 20261018000700_suggestions.sql and SuggestionCatalog.kt.

export interface CatalogEntry {
  slug: string;
  label: string;
  description: string;
  hints: string;
}

export function documentOf(entry: CatalogEntry): string {
  return `${entry.slug.replaceAll("_", " ")} ${entry.label} ${entry.description} ${entry.hints}`;
}

export const CATALOG: CatalogEntry[] = [
  { slug: "historical_fact", label: "Historical fact", description: "Something that happened here, ideally with a source.", hints: "history historical museum happened year source archive" },
  { slug: "story", label: "Story", description: "A personal or local story tied to this spot.", hints: "story tale personal memory anecdote grandmother" },
  { slug: "memorial", label: "Memorial", description: "Remembering a person or event connected to this place.", hints: "memorial plaque remember remembrance candle" },
  { slug: "viewpoint", label: "Viewpoint", description: "A view worth stopping for.", hints: "viewpoint view overlook vista scenic lake panorama overlook scenic vista" },
  { slug: "trail_note", label: "Trail note", description: "Conditions, turns, and tips on a trail.", hints: "trail hike path muddy turn conditions hiking" },
  { slug: "wildlife", label: "Wildlife sighting", description: "An animal or plant seen here recently.", hints: "wildlife animal bird owl deer fox plant sighting owl owl" },
  { slug: "water_source", label: "Water source", description: "Drinking fountains, springs, and refill spots.", hints: "drinking fountain spring refill water bottle tap" },
  { slug: "food_truck", label: "Food truck", description: "A truck or cart serving here right now.", hints: "food truck cart taco street kitchen trailer lunch vehicle" },
  { slug: "eatery_tip", label: "Eatery tip", description: "What to order, when to go, what to skip.", hints: "restaurant cafe diner menu order dish plate eatery" },
  { slug: "market", label: "Market", description: "Farmers markets, night markets, and stalls.", hints: "farmers market stall produce vendor bazaar" },
  { slug: "event", label: "Event", description: "Something happening here at a set time.", hints: "event festival concert fair schedule happening show" },
  { slug: "popup", label: "Pop-up", description: "A short-lived pop-up shop, show, or stand.", hints: "popup temporary shop stand kiosk" },
  { slug: "yard_sale", label: "Yard sale", description: "A yard, garage, or stoop sale.", hints: "yard sale garage sale stoop" },
  { slug: "for_sale", label: "For sale", description: "One item for sale, picked up here.", hints: "for sale item pickup price selling" },
  { slug: "free_stuff", label: "Free stuff", description: "Curb alerts and giveaways.", hints: "free stuff giveaway curb couch freebie" },
  { slug: "hazard", label: "Hazard", description: "Road, sidewalk, or flood hazards.", hints: "hazard flood pothole sidewalk closed ice road warning" },
  { slug: "safety_alert", label: "Safety alert", description: "Something people nearby should know about now.", hints: "safety alert warning caution danger nearby" },
  { slug: "incident_report", label: "Incident report", description: "A crime or incident that happened nearby. Approximate location only.", hints: "incident crime theft break-in robbery police report" },
  { slug: "place_of_worship", label: "Place of worship", description: "Churches, mosques, temples, synagogues, and more.", hints: "church mosque temple synagogue worship service faith" },
  { slug: "community_space", label: "Community space", description: "Libraries, centers, gardens, and halls open to the public.", hints: "library community center hall garden public room" },
  { slug: "notice_board", label: "Notice", description: "A community notice for people nearby.", hints: "notice announcement board flyer community" },
  { slug: "infrastructure", label: "Infrastructure", description: "Bridges, utilities, and how the city works.", hints: "bridge utility sewer infrastructure works" },
  { slug: "construction", label: "Construction", description: "What is being built here, and until when.", hints: "construction crane crew building site roadwork scaffold" },
  { slug: "underground", label: "Underground", description: "Tunnels, buried rivers, and what lies beneath.", hints: "tunnel underground buried railroad underpass subway" },
  { slug: "hidden_gem", label: "Hidden gem", description: "Easy to walk past. Worth stopping for.", hints: "hidden gem courtyard secret alley easy to miss" },
  { slug: "tip", label: "Local tip", description: "Something only locals know about this spot.", hints: "local tip locals know shortcut advice" },
  { slug: "street_art", label: "Street art", description: "Murals, graffiti, and paste-ups.", hints: "mural graffiti street art wall painting paste" },
  { slug: "public_art", label: "Public art", description: "Sculptures, installations, and monuments.", hints: "monument sculpture statue installation bronze public art" },
  { slug: "music_spot", label: "Music spot", description: "Buskers, open mics, and great acoustics.", hints: "music busker guitar open mic acoustic singer" },
  { slug: "note", label: "Note", description: "Anything else worth knowing about this spot.", hints: "note miscellaneous other general" },
];
