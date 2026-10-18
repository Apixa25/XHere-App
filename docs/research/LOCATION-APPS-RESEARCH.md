# XHere: Location-Data Apps Research and Recommendations

*Researched 2026-10-18 (America/New_York) for Steven Sills II. Every number below links to its source. Where a source is a third-party estimate (e.g. Sensor Tower), it says so. This builds on `PLAN.md`, `OPEN-DATA-PLAYBOOK.md`, and `CRIME-OPEN-DATA.md`; it does not replace them.*

**One-line takeaway:** The apps people love give an **instant, personal payoff for being somewhere** (Waze saves you time, Pokémon GO gives you a reward, iNaturalist tells you what you're looking at). The ones that die either **had no reason to come back**, **got toxic**, or **never found money**. XHere's edge is Steven's theme: *open the data that already exists about this exact spot*, so the map is full on day one and people only have to add what's missing.

---

## 1. The landscape: who does location data today

### 1.1 Summary table

| App | Core data | Why people contribute | Retention hook | Money | Failures / lessons |
|---|---|---|---|---|---|
| **Waze** | Live road reports, map edits | Helping other drivers; points and levels; edits go live | Saves time on every drive; "There / Not there" confirms | Ads (part of Google) | Needs a lot of people driving at once; useless offline |
| **Google Maps / Local Guides** | Reviews, photos, edits, Q&A | Points, levels, badges, seeing your photo views | Default map for 2B+ users | Ads, Maps Platform API | Fake reviews; removed "follow contributors" in Sept 2025 over bot spam |
| **Yelp** | Reviews of businesses | Elite status, events, community managers | Deciding where to eat | Ads to businesses | Trust fights over review filtering |
| **Nextdoor** | Neighbor posts, alerts | Local belonging | Alerts, local news | Ads | Racial-profiling posts (fixed with form friction); WAU fell after its 2025 relaunch |
| **Citizen** | 911/scanner-sourced incidents + user video | Fear and curiosity | Push alerts | $20/mo "Protect" subscription | Banned as "Vigilante"; CEO's $30k bounty on an innocent man; layoffs |
| **Foursquare / Swarm** | Check-ins, tips, venues | Mayorships, streaks, stickers | Personal travel diary (Swarm) | Sold the **data** (B2B), not the app | City Guide app shut down Dec 2024; open-sourced 100M+ places |
| **Strava** | GPS activities, segments | Kudos, segment leaderboards, Year in Sport | Social fitness feed | Subscription | 2018 heatmap exposed military bases |
| **AllTrails** | Trails, reviews, conditions | Help other hikers; reviews | Planning every hike | Subscriptions (Plus, Peak $79.99/yr) | Paywall pushback; made Custom Routes free on desktop Nov 2025 |
| **iNaturalist** | Species observations | Learning what you saw; science | Instant AI ID suggestion, then community confirms to "Research Grade" | Nonprofit (grants) | Community backlash to a Google AI grant |
| **Geocaching** | Hidden containers + logs | The hunt; logging finds | Treasure hunting nearby | Premium membership | Needs physical maintenance |
| **Pokémon GO / Niantic** | Game spots (PokéStops), player-nominated places | Getting a spot near home; game rewards | Daily game loop, events | In-app purchases | Spot review is slow; games sold to Scopely in 2025 |
| **Snap Map** | Friends' locations, public Snaps | Friends, what's happening | Built into Snapchat | Ads, "Promoted Places" | Privacy worry (Ghost Mode added) |
| **Zenly** | Friends' live location | Fun, expressive friend map | Daily habit with friends | **None** | Shut down by Snap in Feb 2023: no revenue |
| **Life360 / Find My** | Family location, item trackers | Safety for family | Peace of mind | Subscription (Life360) / hardware (Apple) | Stalking risk led to Apple–Google unwanted-tracker standard |
| **Historypin** | Old photos/stories pinned to places + dates | Sharing heritage | Then-and-now views | Nonprofit projects | Low daily reason to open |
| **Clio** | Historical site entries + walking tours | Educators and historians | Tours | Free, university-backed | Small, editorial |
| **Atlas Obscura** | Curated "wondrous places" | Being published | Travel inspiration | Media, trips, membership | Editorial review limits scale |
| **what3words** | 3-word grid addresses | n/a (not user content) | Emergency use | B2B licensing | Small revenue vs. big losses; ambiguity criticism |
| **Mapillary / OpenStreetMap** | Street-level imagery / open map data | Open map mission, campaigns | Seeing your coverage; leaderboards | Meta-backed / donations | Hard for beginners |
| **Waymarking** | Categorized interesting spots | Collecting | Weak | Groundspeak side project | Forums moved to read-only archive in 2013 |
| **Sekai Camera** | AR "Air Tags" floating over places | Novelty | Weak | — | Shut down Jan 22, 2014; all Air Tags deleted |
| **Direct look-alikes (2025)**: Koko, Wakestow, Auglinn | Geo-notes, some unlock on the spot | Novelty | Mostly none yet | — | Show the idea is "in the air"; none has scale |

### 1.2 Key facts, with sources

**Waze**
- About **180 million monthly active users** ([Waze Help, About Waze](https://support.google.com/waze/answer/6071177?hl=en)).
- In 2025 the community made **247 million edits**, had **80,000 active editors**, updated **4.8 million places**, and added **3.9 million closures** ([Waze Community Newsletter, Dec 2025](https://www.waze.com/discuss/t/newsletter-da-comunidade-dezembro-de-2025-comemore-os-247-milhoes-de-edicoes-em-2025/395841)).
- Points table: a report is 6 points, a voice report 9, and tapping "There"/"Not there" on someone else's alert 2 ([Waze Help, Earn points](https://support.google.com/waze/answer/6273662?hl=en)). *Confirming is rewarded, not just posting.*
- Editors asked for impact numbers like "how many Wazers we helped… how many said thanks" (same Dec 2025 thread). That's a clear unmet need: **show contributors their impact**.
- Waze's "Edit Suggestions" (new users suggest, experienced editors approve): ~250,000 created, **86% approval**, average 2-day resolution, and it "drastically increased the number of new active editors." Waze also added guided tasks for new editors and an AI assistant planned for 2026 ([Waze Office Hours, Nov 2025](https://www.waze.com/discuss/t/office-hours-de-novembro-de-2025-painel-de-atualizacoes-do-wme/391952)).

**Google Maps / Local Guides**
- **2 billion+ monthly users** ([9to5Google, Oct 2024](https://9to5google.com/2024/10/29/google-maps-2-billion/); reaffirmed in [Google's 20-year post](https://blog.google/products-and-platforms/products/maps/20-years-google-maps-20-features/)).
- Points: review 10 (+10 bonus for 200+ characters), photo 5, place added 15, fact check 1. Levels 1–10 run from 0 to 100,000 points, and the badge appears at Level 4 ([Local Guides points](https://support.google.com/local-guides/answer/6225851?hl=en)).
- Follow-a-contributor was removed in Sept 2025, partly over bot-follower spam ([Tom's Guide](https://www.tomsguide.com/phones/google-maps-is-losing-these-two-features-heres-whats-changing), [Android Police](https://www.androidpolice.com/google-maps-follow-feature-to-be-discontinued/)).
- Since Jan 2025, listings suspected of fake reviews get a consumer-alert banner and can be frozen for new reviews ([Local Guides Connect](https://www.localguidesconnect.com/t/nothing-to-be-scared-of-new-consumer-alert-message-in-your-profile/469874)).
- **Ask Maps** (Gemini, launched Mar 2026 in the US and India) answers detailed questions using 300M+ places and reviews from 500M+ contributors ([Google blog](https://blog.google/products-and-platforms/products/maps/ask-maps-immersive-navigation/)). Gemini navigation, **landmark directions**, and "Lens with Gemini" (point the camera at a place and ask) arrived in Nov 2025 ([Google blog](https://blog.google/products-and-platforms/products/maps/gemini-navigation-features-landmark-lens/)).

**Yelp**
- **330 million cumulative reviews**, with 22 million added in 2025 ([Yelp FY2025 results](https://www.yelp-ir.com/news/press-releases/news-release-details/2026/Yelp-Delivers-Record-Net-Revenue-in-2025-Accelerating-Investment-in-AI-Transformation/default.aspx), [Fast Facts](https://www.yelp-press.com/company/fast-facts/)). Uses LLM review summaries that quote real reviews.
- **Cold-start playbook:** launch city by city. A local community manager seeds the first reviews and recruits passionate locals, then newsletters and events turn them into the Elite community. Yelp gave status, not cash, because "when you growth hack with incentives, you erode authenticity" ([CMX on Yelp Elite](https://www.cmxhub.com/blog/yelp-elite-nish-nadaraja-psychology), [Laura Nestler interview](https://gettogether.world/transcript/laura-nestler)).

**Nextdoor**
- Platform WAU was 21.8M in Q2 2025 and **21.0M in Q4 2025 (−5% YoY)** after the July 2025 relaunch (News, Alerts, AI "Faves"). Full-year revenue was $258M, and adjusted EBITDA turned positive for the first time ([Q4 2025 release](https://about.nextdoor.com/press-releases/nextdoor-reportsfourth-quarter-and-full-year2025-results), [Q2 2025 investor update](https://www.sec.gov/Archives/edgar/data/1846069/000184606925000101/exhibit991-q22025nextdoo.htm)). The relaunch moved toward "neighbors + authoritative sources" and AI summaries, the same direction as XHere's open-data layers.
- **Moderation lesson:** adding friction to the Crime & Safety form (describe behavior, not race; require non-race details first) cut racial-profiling posts by **75%**, while draft abandonment rose 50% ([WIRED](https://www.wired.com/2016/08/nextdoor-breaks-sacred-design-rule-end-racial-profiling/), [GeekWire](https://www.geekwire.com/2016/qa-nextdoor-ceo-explains-social-network-cracking-racial-profiling/)). XHere's M7 Safety flow already does this.

**Citizen**
- Launched as "Vigilante" and was pulled from the App Store. The CEO put a $30,000 bounty on a man who turned out to be innocent. Citizen Protect costs about $20/mo and had 100,000 users by Jan 2022. Sensor Tower estimates about $30.3M lifetime consumer spending, followed by layoffs in 2023 ([TechCrunch 2022](https://techcrunch.com/2022/01/26/crime-tracking-app-citizen-acquires-harbor-a-disaster-prep-app/), [TechCrunch 2023](https://techcrunch.com/2023/01/12/crime-reporting-app-citizen-lays-off-33-employees/), [Vox](https://www.vox.com/recode/2021/8/3/22605791/citizen-protect-safety-app), [NBC News](https://www.nbcnews.com/tech/tech-news/citizen-public-safety-app-pushing-surveillance-boundaries-rcna1058)).
- **Lesson:** a crime-first map earns attention but loses trust and invites harm. In XHere, safety is one layer among many and is the strictest.

**Foursquare**
- The City Guide app shut down Dec 15, 2024, and the web version on Apr 28, 2025. Swarm continues ([Foursquare](https://foursquare.com/city-guide-sunset/)).
- **Foursquare OS Places**: 100M+ POIs, 1,000+ categories, **Apache 2.0**, updated monthly ([Foursquare OS Places](https://opensource.foursquare.com/os-places/)). It's free seed data for XHere's "what's here" base layer.
- **Lesson:** check-in gamification spiked and then faded. The lasting value was the **places data**.

**Strava / AllTrails**
- Strava: 180M+ users in 185+ countries ([Strava Year in Sport 2025](https://press.strava.com/articles/strava-releases-12th-annual-year-in-sport-trend-report-2025)). Its 2018 heatmap exposed military bases, and Strava then restricted detail ([Guardian](https://www.theguardian.com/world/2018/jan/28/fitness-tracking-app-gives-away-location-of-secret-us-army-bases), [Reuters](https://www.reuters.com/article/us-strava-privacy-exclusive/exclusive-fitness-app-strava-overhauls-map-that-revealed-military-positions-idUSKCN1GP1WE/)). **Lesson:** aggregate location data can leak secrets. Never publish raw user movement.
- AllTrails: 90M registered users, 450,000+ trails ([AllTrails Year in Review 2025](https://publiclands.alltrails.com/yearinreview2025)). The Peak tier ($79.99/yr) adds AI Custom Routes, Trail Conditions, and heatmaps ([AllTrails press](https://www.alltrails.com/press/alltrails-expands-membership-offering-with-alltrails-peak)). AllTrails also launched inside ChatGPT ([AllTrails press](https://www.alltrails.com/press/introducing-alltrails-in-chatgpt)), a sign that **local data is moving into AI assistants**.

**iNaturalist**
- **250M verifiable observations** from nearly 4M observers and 400,000+ identifiers ([iNat blog](https://www.inaturalist.org/posts/113107-we-ve-reached-250-million-verifiable-observations)), later **300M** ([iNat blog](https://www.inaturalist.org/blog/126478-inaturalist-at-300-million-observations)).
- The loop: snap a photo, get an **instant AI suggestion**, then the **community confirms** and the record reaches "Research Grade" ([iNat CV](https://www.inaturalist.org/pages/computer_vision_demo); motivation research in [Citizen Science: Theory and Practice](https://theoryandpractice.citizenscienceassociation.org/articles/10.5334/cstp.823)). This is the closest model to XHere's suggest-category + "Still true?" design.
- It drew community outcry over a Google AI grant ([Scientific American](https://www.scientificamerican.com/article/google-ai-grant-to-inaturalist-prompts-community-outcry/)). **Lesson:** be upfront with contributors about AI use of their data.

**Geocaching**
- About **3.4M active geocaches** in 25 years ([Geocaching Newsroom](https://newsroom.geocaching.com/25-years-outdoor-adventure)), with a Premium membership ([Premium](https://newsroom.geocaching.com/premium)). It proves that *"something hidden here that only reveals when you arrive"* (XHere's reveal radius) is a durable hook.

**Pokémon GO / Niantic**
- **$1B+ revenue in 2025** and 800M lifetime players ([PocketGamer.biz](https://www.pocketgamer.biz/pokemon-go-marks-10-years-with-800m-players-and-a-trillion-pokemon-caught/)).
- **Cold start:** the original PokéStops came from Ingress portals. Ingress itself was **seeded from libraries, museums, historical markers, and public art**, then grew from player nominations ([GamesBeat, John Hanke](https://gamesbeat.com/the-accidental-history-of-niantics-pokemon-go-as-told-by-john-hanke/2/)). That is exactly XHere's "seed from open data, then let locals add" plan.
- Wayfarer (player-nominated spots with community review) handles about 100,000 contributions a month, and users complain about slow review and appeals ([Wayfarer AMA 2025](https://community.wayfarer.nianticlabs.com/t/general-wayfarer-ama-discussion/108903?page=11)).
- 2025: games sold to Scopely for $3.5B. **Niantic Spatial** was spun out to build a "Large Geospatial Model" from 30B+ posed images ([Reuters](https://www.reuters.com/markets/deals/pokemon-go-maker-niantic-sell-game-division-saudi-owned-scopely-35-billion-2025-03-12/), [Niantic Spatial](https://www.nianticspatial.com/blog/largegeospatialmodel)).

**Snap Map / Zenly**
- Snap Map: **400M+ monthly users**, plus "Promoted Places" for businesses ([Snap Newsroom, May 2025](https://newsroom.snap.com/2025-snap-map-mau)).
- Zenly was popular but earned no meaningful revenue, and Snap wound it down to focus on Snap Map ([Snap restructuring](https://newsroom.snap.com/restructuring-and-refocusing-our-business), [TechCrunch](https://techcrunch.com/2022/09/01/zenly-is-still-hugely-popular-so-whys-snap-shutting-it-down/), [Rest of World](https://restofworld.org/2022/fearing-competition-snap-decided-to-shut-down-zenly-rather-than-sell-it/)). **Lesson:** popularity isn't enough. Have a cheap-to-run setup and a future money path (claimed places, official/business tier) even if it's off for now.

**Life360 / Find My**
- Life360: ~**95.8M MAU** and 2.8M paying circles at the end of 2025 ([Life360 Q4 2025](https://investors.life360.com/news-releases/news-release-details/life360-reports-record-q4-2025-results)). People pay for **safety and peace of mind**.
- Apple and Google co-wrote an unwanted-tracker standard (IETF draft), with alerts on iOS 17.5+ and Android 6+ ([Apple Newsroom](https://www.apple.com/newsroom/2024/05/apple-and-google-deliver-support-for-unwanted-tracking-alerts-in-ios-and-android/), [IETF draft](https://www.ietf.org/archive/id/draft-ledvina-apple-google-unwanted-trackers-02.html)). **Lesson:** object broadcast is real, and anti-stalking rules come with it.

**History apps (Historypin, Clio, HMdb, Atlas Obscura)**
- Clio: 40,000+ entries and 1,700+ walking tours ([UMKC / David Trowbridge](https://research.umkc.edu/dtrowbridge/research), [Clio](https://theclio.com/)).
- Historypin: pin old photos and stories with a place and date. Others can suggest a better location or date, and the original contributor is notified ([Historypin FAQ](https://www.about.historypin.org/faq-1)).
- HMdb: 240,868 US and 8,231 Canada markers listed. Its 250,000th marker came in Dec 2025 ([HMdb](https://www.hmdb.org/), [Wikipedia](https://en.wikipedia.org/wiki/Historical_Marker_Database)). **Licensing:** non-commercial copying only with attribution, and commercial use needs written permission ([HMdb copyright](https://www.hmdb.org/copyright.asp)). For XHere that means **link-out, or ask permission**; don't bulk import.
- Atlas Obscura: ~33,700 places and foods, editor-reviewed ([About](https://www.atlasobscura.com/about)). We found no open data license, so treat it as **link-out only**.

**what3words, OSM/Mapillary, Waymarking, Sekai Camera**
- what3words: about £2.2M revenue vs. £10.6M losses in 2024 (reported via [Terence Eden's analysis](https://shkspr.mobi/blog/2025/02/endless-downward-spiral-is-this-the-beginning-of-the-end-of-what3words/)). An academic critique of ambiguity is in [PMC](https://pmc.ncbi.nlm.nih.gov/articles/PMC10599581/). **Lesson:** a clever location format alone isn't a business. Use open Plus Codes or coordinates if XHere ever needs share-codes.
- Mapillary: 2.4B+ images, plus "CompleteTheMap" campaigns with OSM US. One campaign covered 17,639 unique miles and ~7.5M images in 2 months ([Mapillary intro](https://help.mapillary.com/hc/en-us/articles/115001770269-An-Introduction-to-Mapillary), [CompleteTheMap US](https://forum.mapillary.com/t/completethemap-us/9929)). **Lesson:** time-boxed community campaigns work.
- Waymarking: forum moved to a read-only archive ([Waymarking news](https://staging.waymarking.com/news/default.aspx)). Sekai Camera: AR "Air Tags," shut down Jan 22, 2014, with all tags deleted ([WIRED](https://www.wired.com/2014/01/augmented-reality-sekai-camera/)). **Lesson:** AR-first and collect-for-its-own-sake both faded without a daily reason to open the app.

---

## 2. What makes contribution loops work, and why they fail

### 2.1 What works (patterns that keep showing up)
1. **Contribute while doing the thing you came for.** Waze reports happen mid-drive in one tap. AllTrails reviews come right after a hike. *XHere: prompt "anything worth dropping here?" only after a reveal or a long stop, never as a nag.*
2. **Instant feedback.** iNaturalist's AI suggestion arrives in seconds, and Waze shows your report on the map right away. *XHere already has suggest-category. Add "your drop is live, 1st in this block."*
3. **Confirming counts as contributing.** Waze gives 2 points per "There/Not there," and Google Maps gives points for fact checks. Confirming is the lowest-effort way in for the 90%. (Nielsen's 90-9-1 rule of thumb says most users only read: [NN/G](https://www.nngroup.com/articles/participation-inequality/).)
4. **Show impact.** Waze editors explicitly asked for "how many people we helped." *XHere: a "Seen by 37 · Confirmed by 4 · 2 thanks" line on each drop, plus a weekly digest.*
5. **Status, not cash.** Yelp Elite, Local Guides levels, Waze levels. Paying for content erodes authenticity (Yelp's own community lead).
6. **Guided first tasks.** Waze Edit Suggestions and guided tasks grew new editors. *XHere: a first-session "starter quest" (confirm 1, drop 1, unlock 1).*
7. **Something only here.** Geocaching and Pokémon GO reward being physically present. XHere's reveal radius is the same hook.
8. **Campaigns and local champions.** Yelp city launches, Mapillary CompleteTheMap, Waze Map Raids.

### 2.2 Why they fail
| Failure | Example | XHere guard |
|---|---|---|
| **Empty map / cold start** | Most geo-note apps (Sekai Camera; today's Koko-style apps) | Seed every region from open data before launch (see §4.2) |
| **No reason to return** | Waymarking, Sekai Camera, Foursquare check-ins | Daily-useful layers (conditions, events, food trucks), freshness, quests |
| **Toxicity / harassment** | Yik Yak (anonymity: [Tandfonline study](https://www.tandfonline.com/doi/full/10.1080/17439884.2019.1583672)), Nextdoor profiling, Citizen vigilantism | Named accounts, tiered trust, descriptor filter, pre-moderation for sensitive categories (M7 done) |
| **Spam / fake content** | Google fake reviews, Maps follower bots | Rate limits by tier, "Still true?" from people *on the spot*, anti-spoof (M12) |
| **Privacy leaks** | Strava heatmap; stalking via trackers | Never publish user tracks or heatmaps of people; fuzz sensitive pins; foreground-only location |
| **No money path** | Zenly | Keep costs at $0 now, but design claimed places / official org tier for later 💲 |
| **Slow review frustrates contributors** | Niantic Wayfarer complaints | Post-moderation by default; pre-moderation only where needed; clear appeal |
| **AI use of community data without consent** | iNaturalist backlash | Say plainly in terms and in-app how AI uses drops |

---

## 3. What location data will be valuable next (2025–2026 trends)

1. **AI that answers "what's here?"** Google Ask Maps (Mar 2026), Gemini landmark navigation and Lens (Nov 2025), Yelp LLM summaries, Nextdoor AI Faves. Google sells this as **Grounding with Google Maps** in the Gemini API and a **Maps Grounding Lite MCP server** for AI agents ([Google blog](https://blog.google/innovation-and-ai/technology/developers-tools/grounding-google-maps-gemini-api/), [Grounding Lite](https://developers.google.com/maps/ai/grounding-lite)). Big players cover *businesses*. **The gap is everything that isn't a business**: history, civic records, local stories, hazards, hidden spots. That's XHere's lane.
2. **Local knowledge for AI agents.** AllTrails is inside ChatGPT, and Google exposes Maps over MCP. A future XHere **public read API / MCP endpoint** ("ask XHere what's known about this spot," with citations) could make XHere a source *for* AI, not just an app.
3. **Open government data finally reachable.** Open311 GeoReport v2 has live endpoints in many cities (Boston, Chicago, SF, DC, Toronto, Helsinki…) ([Open311 servers](https://wiki.open311.org/GeoReport_v2/Servers/)). NWS alerts are free with no key ([api.weather.gov](https://www.weather.gov/documentation/services-web-api)). The NRHP national historic register is free GIS, including an ArcGIS REST service ([NPS](https://nps.gov/subjects/nationalregister/data-downloads.htm), [REST](https://mapservices.nps.gov/arcgis/rest/services/cultural_resources/nrhp_locations/MapServer)). Wikipedia GeoSearch covers up to a 10 km radius ([MediaWiki](https://www.mediawiki.org/wiki/API:Geosearch)). OpenAQ air quality is free with a key ([OpenAQ](https://docs.openaq.org/using-the-api/api-key)). Permits, inspections, and code cases are covered in `OPEN-DATA-PLAYBOOK.md`.
4. **Open base-map data is consolidating.** Overture Maps (Amazon, Meta, Microsoft, and others) now includes ~6M extra Foursquare POIs (Apache 2.0) and, by Sept 2026, an `operating_status` (open / temporarily / permanently closed) and stable **GERS IDs** ([Overture 2025-09 notes](https://docs.overturemaps.org/blog/2025/09/24/release-notes/), [2026-09 notes](https://docs.overturemaps.org/blog/2026/09/23/release-notes/), [GERS](https://docs.overturemaps.org/gers/registry/)). *Note: public releases are only kept for 60 days, so pin a release and cache what we use.* GERS IDs would let XHere drops attach to "the same place" other datasets talk about.
5. **Real-time conditions beat static facts.** Waze's value is live (3.9M closures in 2025), and AllTrails charges for Trail Conditions. "Is it true **right now**?" (closed, flooded, line is long, food truck here) is what people come back for daily.
6. **AR and spatial anchors are real but still early.** The ARCore Geospatial API (rooftop/terrain anchors from Street View) is free within quotas, with up to 100 terrain/rooftop anchors per session ([ARCore quota](https://developers.google.com/ar/develop/c/geospatial/api-usage-quota), [ArEarth](https://developers.google.com/ar/reference/c/group/ar-earth)). Niantic Spatial is building a geospatial model for AR and robots. AI glasses shipments are growing fast (Counterpoint: +139% YoY in H2 2025, Meta 82% share; [Counterpoint](https://counterpointresearch.com/en/insights/Global-Smart-Glasses-Shipments-Grew-139-Percent-YoY-in-H2-2025)). *Takeaway:* make every drop **AR-ready** in the data model (lat/lng/altitude/heading optional) but keep AR a v2 feature. Sekai Camera shows AR-first is too early on phones alone.
7. **Privacy-first location is the new normal.** Google moved Timeline on-device ([Google](https://blog.google/products-and-platforms/products/maps/updates-to-location-history-and-new-controls-coming-soon-to-maps/)), and Apple's iOS 26 "Visited Places" is on-device and end-to-end encrypted ([Apple](https://support.apple.com/guide/iphone/view-and-manage-visited-places-iph0c2597c4f/ios)). XHere should never keep a user's location trail on the server. That's already the plan (foreground-only, check-and-discard). It's also a selling point.
8. **Objects that broadcast where they are.** Find My / Find My Device networks and the tracker standard show that "things" will carry location. A future XHere idea is **"official beacons"**: a museum or city posts a drop tied to a QR/NFC plaque. QR/NFC is free and simpler than BLE.

---

## 4. Recommendations for XHere

### 4.1 Ranked features and data layers (value vs. effort, all free unless 💲)

| # | Feature / layer | Why (evidence) | Value | Effort | Fits milestone |
|---|---|---|---|---|---|
| **1** | **"What's here" open-data base layer**: Wikipedia/Wikidata + **NRHP** + OSM `historic=*`/`tourism=*` + Overture/Foursquare places, with source badges and attribution | Beats the empty map (Ingress seeded from markers and libraries); it's Steven's "data already out there" theme | ★★★★★ | M | M10 (expand scope from Wikipedia-only) |
| **2** | **Impact feedback**: "Seen by N · Confirmed by N · thanks" on drops, a "your drop helped" push, a weekly digest | Waze editors ask for exactly this; it's the cheapest retention lever | ★★★★ | S | M11 (small, can ship first) |
| **3** | **Here Card**: tap any spot to get a one-screen summary of *all* layers there (drops + open data + conditions), with citations | The same need as Ask Maps / Lens, but for non-business data | ★★★★★ | M (S without AI text) | New (M10b). Template-based is free; LLM summary is 💲 later or on-device Gemini Nano on supported phones |
| **4** | **Trails & quests**: walking tours from seeded history (e.g. a Jamestown downtown history walk), plus a starter quest for new users | Clio has 1,700+ tours; Waze guided tasks grew editors; gives a reason to walk around and unlock | ★★★★ | M | M14 (pull forward a simple version) |
| **5** | **Live conditions layer**: NWS alerts (free, no key), Open311 where a city has it, and user "right now" drops (closed, flooded, line, truck here) with short lifetimes | Daily-return reason (Waze, AllTrails Trail Conditions) | ★★★★ | S–M | M16a + new adapter `nws_alerts` |
| **6** | **Founding Locals program**: the first ~12 Play testers become "Founding Locals" with a badge; a time-boxed "Map Jamestown" campaign | Yelp city launch, Mapillary CompleteTheMap, Waze Map Raids | ★★★★ | S (mostly non-code) | M11 badges + pilot ops |
| **7** | **Reputation tiers + ~10 badges** (already planned) | Local Guides / Waze levels; status, not cash | ★★★ | M | M11 |
| **8** | **XHere read API / MCP endpoint** for AI agents (public, cited, rate-limited, safe layers only) | AllTrails in ChatGPT and Google Maps MCP show where discovery is going | ★★★ (rising) | M | Later (after M10) |
| — | AR view on ARCore Geospatial | Real but early; free within quota | ★★ now | L | v2 (M17). Store optional altitude/heading on drops now |
| — | QR/NFC "official plaque" drops for museums and cities | Bridges physical and digital; leads to a claimed-places tier | ★★ | S | With claimed places (M17) |

### 4.2 Beating cold start: region-agnostic seeding playbook
Extends `OPEN-DATA-PLAYBOOK.md`. Per region (YAML config, no code):
1. **Global layers, automatic everywhere:** Wikipedia/Wikidata geosearch, OSM (`historic`, `memorial`, `tourism=artwork|viewpoint`, `amenity=library|place_of_worship`), Overture/Foursquare places (pinned release, cached), Wikimedia Commons geotagged photos (CC licenses, attribution).
2. **National heritage register:** US NRHP (free GIS), UK Historic England list, and the equivalent elsewhere (playbook §1 table).
3. **Live layers:** a weather-alert source (US NWS; others per country), Open311 if listed, and city open-data portals (Socrata/ArcGIS/CKAN adapters).
4. **Link-out only:** HMdb, Atlas Obscura, Clio, and local news. Show "More about this place →" links and never copy their text. Ask HMdb for permission if markers become important.
5. **Seed density goal:** before opening a region, every downtown block should show ≥1 item. A CI check reports the count per H3 cell.
6. **Humans:** 5–10 "Founding Locals" plus one local partner (library or historical society) adding stories and photos the open data lacks. Run a 2-week campaign with a leaderboard.
7. **Never fake users:** seeded items carry `source=open_data` badges. No bot-written "user" drops.

### 4.3 Trust and moderation (keep, plus small additions)
- Keep: "Still true?" from people on the spot, tiered trust, descriptor filter and tip-line-first Safety flow, pre-moderation for sensitive categories, audit log (all in M6/M7).
- Add: **suggest-an-edit** on open-data items (Waze Edit Suggestions / Historypin style), reviewed by Trusted users. A plain-language **AI-use notice** (iNaturalist lesson). **No public people-heatmaps, ever** (Strava lesson). **No bounties or "find this person" features** (Citizen lesson). Plus a clear appeal path with a time target (Wayfarer lesson).

### 4.4 What to build next (suggested order)
1. **M10 (expanded): open-data base layer.** Wikidata/Wikipedia + NRHP + OSM historic, with the generic adapter pipeline from the playbook, configured for `us-ny-chautauqua`. *This is the biggest visible jump for Steven's test phone: Jamestown stops looking empty.*
2. **Impact feedback** (small PR): seen/confirmed counters on drops and the profile, plus a "your drop helped" notification.
3. **Here Card** (template version, no paid AI).
4. **Jamestown history walk + starter quest** (simple quest table, auto-validated by reveal).
5. **NWS alerts + "right now" conditions drops.**
6. Then M11 badges/tiers with "Founding Locals," then M12 anti-spoofing before a public launch.

**Costs flagged:** none of the above needs money. 💲 items stay deferred: a server LLM for natural-language Here Card summaries, Google Maps Platform/Grounding (paid API), paid crime aggregators, Play Console ($25, already pending).

---

*Sources were accessed 2026-10-18. Third-party estimates (Sensor Tower, Counterpoint) are labeled. Nothing was copied from sources beyond short facts.*
