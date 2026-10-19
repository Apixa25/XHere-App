# Everything Has a Voice

An object — a bench, a mural, a shop door, a museum piece, someday one specific chair — broadcasts its own story. A phone hears that broadcast from about 30 feet (10 meters) **without a tap**. GPS is not the location indoors. The broadcast is.

This is the owner's hard rule. [PLAN.md](PLAN.md) §1 is the Kotlin Multiplatform Android app and the Supabase backend. PLAN §3 is proximity on the map, with AR later, and it does not add background location. PLAN §5 is the privacy rule: XHere does not keep a trail of where a person walked, and it does not store who heard which object. [docs/research/LOCATION-APPS-RESEARCH.md](research/LOCATION-APPS-RESEARCH.md) §3.8 is the anti-stalking lesson from Find My, and §4.1 ranks a QR or NFC plaque as a later small feature. That research note is why NFC exists here as a close-range extra. It is not how an object is heard. BLE broadcast is the core.

No hardware is purchased for this. The first advertiser is a second phone.

## The id

The canonical id is **6 bytes, 12 lowercase hex characters** (`voice_id`). It fits in one legacy advertising packet. A legacy packet is **31 bytes**. A harvesting tag does not open a connection and does not send a second packet to finish the id.

| Form | Example | Who uses it |
|---|---|---|
| Voice id | `0123456789ab` | The public URL and the open API |
| XHere key | `xhere:0123456789ab` | The compact service-data packet, and `resolve_things` |
| Eddystone-UID | `uid:<20 hex namespace>:<12 hex instance>` | A configured beacon |
| Eddystone-URL | `url:<exact url>` | A beacon that already broadcasts a short URL |
| iBeacon | `ibeacon:<uuid>:<major>:<minor>` | Major and minor are 0–65535, no leading zeros |

The XHere Eddystone-UID namespace is 10 bytes, hex `58484552450100000001` (ASCII `XHERE`, then version 1). When the instance is a voice id, the signed-in resolver maps it to that thing with no extra bind. A foreign namespace, a foreign iBeacon, or a foreign URL has to be bound by the owner after the phone hears it.

`https://xhere.world/t/0123456789ab` and `url:xhere://t/0123456789ab` also map to that voice id.

Eddystone-URL cannot carry a new voice id. The encoded URL is at most 17 bytes after the scheme prefix. `https://xhere.world/t/` plus 12 hex is longer than that. Eddystone-URL is only for a beacon that already broadcasts some short URL, stored as `url:<exact>`.

## The advertising payload

Provisional 16-bit UUID **`0xFCE1`**. It is **not** assigned by the Bluetooth SIG. The 128-bit form used by Android is `0000fce1-0000-1000-8000-00805f9b34fb`. Eddystone remains `0xFEAA`. iBeacon remains Apple company id `0x004C`, prefix `02 15`.

The compact service-data payload is 8 bytes: version `0x01`, the 6-byte id, and the calibrated RSSI at 1 meter (planning value **−59 dBm**).

One legacy packet a phone can send, 19 bytes:

```text
02 01 06
03 03 E1 FC
0B 16 E1 FC 01 <6-byte id> <tx at 1 m>
```

That is flags, a 16-bit service-UUID list (so a scan filter can match), and the service-data AD. A harvesting tag that cannot spare the flags may send the service-data AD alone. Parsers accept either.

Android `AdvertiseData.addServiceData` is given the 8-byte payload. The stack writes the AD header. `ADVERTISE_MODE_BALANCED` is about four times a second, which is above the floor of **4 advertisements a minute**. The advertisement is non-connectable. The phone stops when the process stops. There is no foreground service. Keep XHere open on the broadcasting phone.

Background scan filters are never empty. They are service data `0xFCE1`, service data `0xFEAA`, and manufacturer data `0x004C` masked to the iBeacon prefix `02 15`. `BLUETOOTH_SCAN` is declared with `neverForLocation`. The PendingIntent is `FLAG_MUTABLE` on Android 12 and later. The receiver is not exported.

## Distance

```text
meters = 10 ^ ((txAt1m − rssi) / (10 × 2.2))
```

`2.2` is a middle indoor path-loss exponent. Immediate is under 1.5 m. Near is under 5 m. Far is everything else the radio still delivered. **10 m is Far, and it is still shown.** The bands are labels, not a hide threshold. RSSI 0 is treated as 5 m when a packet has no measurement.

## Resolver

Signed-in, from the phone:

| RPC | Limit | What it returns |
|---|---|---|
| `resolve_things(text[])` | 15 calls / 60 seconds, at most 24 keys | Public active things only. Does not increment `seen_count` |
| `hear_things(text[])` | 48 increments / 10 minutes | How many public things had the aggregate counter increased |

The quota table is `private.thing_rpc_quota` with columns `user_id`, `kind`, `window_start`, `hits`. It has no tag column. The client counts a voice id once per process, in memory, and does not persist who heard it.

Open, no account, on the existing `public-api` function:

```http
GET /v1/things/{id}
```

`{id}` is the 12-hex voice id or the thing uuid. Advertising keys are not accepted, so this route is not an iBeacon directory. The JSON is title, story, category, optional place, optional pin, https media (`url` and `alt` only), `seen_count`, `url`, attribution, and license `xhere-thing`. `owner_id` is not copied even if a row leaked it. Unlisted and removed are `404`. Cache is 120 seconds. The same per-address limit as the rest of `public-api` applies (30 / 60 seconds). The function is `public.public_api_thing(text)`, executable by `service_role` only.

Owners edit through `create_thing`, `update_thing`, `bind_ble_tag`, and `bind_nfc`. Everyone can read a public active row. `seen_count`, `voice_id`, `owner_id`, and `status` are not client-writable. Five distinct open reports set `status` to `removed`. A sensitive category such as `incident_report` cannot be a thing. A fixed pin is optional: indoors the broadcast is the location. When a pin is stored it is rounded to 5 decimals.

## Hardware tiers

No purchase is part of this change.

1. **Phone as beacon.** A signed-in person advertises one thing id from the XHere app. This is how two phones test the 30-foot rule with nothing else to buy. Keep the broadcasting app open.
2. **Off-the-shelf BLE beacon, about $5–20.** Eddystone-UID in the namespace above (instance = voice id, no bind) or any Eddystone-UID, Eddystone-URL, or iBeacon the owner binds by scanning it in "Give this a voice."
3. **Future battery-free tag.** Energy harvesting, Wiliot-style, a target of 10 cents or less, advertising at least 4 times a minute. The packet above is the whole id. No connection. No second packet.
4. **NFC sticker, optional and close-range.** Two NDEF URI records: `https://xhere.world/t/<id>` first, then `xhere://t/<id>`. A tap reads a sticker. It does not qualify as hearing the object.

## App Links, later

`web/src/pages/.well-known/assetlinks.json.ts` already publishes `delegate_permission/common.handle_all_urls` for package `world.xhere.app`. The SHA-256 in `web/src/lib/site.mjs` is a placeholder, not a certificate. xhere.world hosting is suspended, so verification cannot succeed until hosting is back and the Play App Signing SHA-256 replaces that placeholder. The Android intent filters for `/t/` and `xhere://t/` are already in the manifest. Do not invent a fingerprint.

## What to apply on the hosted project

Not applied from this repo. Not applied by CI.

```bash
supabase link --project-ref jpeuldzegtbqfezijhuy
supabase db push
supabase functions deploy public-api --no-verify-jwt
```

The migration is `supabase/migrations/20261021040000_things_voice.sql`. Do not paste it into the dashboard. It does not change Auth. Redeploy `public-api`. There is no new function.
