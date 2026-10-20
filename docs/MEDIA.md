# Media budgets

XHere stores photos and video on the Supabase free plan. [PLAN.md](PLAN.md) §1.4 gives that plan **1 GB of storage** and **5 GB of egress**. §8 keeps the project on that plan until it is worth paying for. The phone therefore does the same thing X and Facebook do: shrink every file on the device, upload the small copy, and show an even smaller picture in lists.

No image-transform add-on and no paid transcoding service. AndroidX Media3 Transformer, which ships with the app, is the video encoder. Bitmap encode is the photo encoder.

This change has not been applied to the hosted project from the agent that wrote it. Apply it with the commands at the end.

## Photos

Drop photos, contribution photos, and the poster frame of a video all use one encode.

| | On the phone | Accepted by the server |
|---|---|---|
| Long edge | 2048 px | 1–2048 px |
| Format | WebP or JPEG, whichever is smaller | `image/jpeg`, `image/png`, `image/webp` still accepted |
| Quality | 85, then 82, then 80 | — |
| Size | Aim under 500 KB. A very noisy picture may shrink the edge (down to 960 px) and, only then, quality 60 | 1–512000 bytes |
| Exif / GPS | Orientation is applied, then the file is re-encoded so GPS and other Exif are gone | `store-contribution-media` strips images again. Drop photos also pass through `strip-exif` |

The phone does not pad a small photo up to 300 KB. A simple picture can be well under that. 300–500 KB is the size a normal camera photo lands at, not a minimum.

GPS is read from the original JPEG **before** this encode, and only to decide whether the photo was taken here. Those coordinates are not stored in the file.

`strip-exif` now refuses a body over **512 KB** (`524288` bytes), which matches the `drop-media` bucket. The phone already compresses before that call.

## Avatars

Avatars stay a square of about **512 px**, JPEG or WebP, at most **512 KB**, with the same 85/82/80 quality band. The bucket is unchanged. A file that is still too big shrinks the square instead of dropping the quality to the old floor of 5.

## Thumbnails

Every new photo also makes a JPEG of about **400 px** on the long edge. The phone keeps that well under 500 KB. The server still accepts a poster up to **512000** bytes so an older thumbnail is not rejected.

Where it is used:

- Community rows sign `thumb_path` when the contribution has one, and the full file otherwise.
- Drop detail shows the sibling in the card and the full photo in the full-screen view.
- The map teaser, the near-me list, and a Here Card row sign the sibling when the card is allowed to show media. Media stays null until the drop is revealed, so a locked drop still has no picture.
- A public-place pin asks Wikipedia and Commons for a **400 px** preview instead of 320 or 480.

Drop thumbnails are not a new column. The object is the photo path with the extension replaced by `.thumb.jpg` (`user/abc.webp` → `user/abc.thumb.jpg`). `private.can_read_drop_media` allows that sibling when the photo itself is readable. If the sign fails, the screen uses the full photo. Photos already in the bucket have no sibling, and they keep working.

Contribution photos may omit `thumb_byte_size`. Older clients still post. A new client sends the thumbnail, and `create_contribution` reserves `c/<contribution>/<media>.thumb.jpg`. A video still requires a poster.

## Video

`ContributionVideo` transcodes on the device with Media3 Transformer before any byte is uploaded.

| | Value |
|---|---|
| Container | MP4, moov atom moved in front of mdat (fast start) |
| Video | H.264, long edge at most 1280 (720p), about 2.25 Mbps, at most 30 fps |
| Audio | AAC at 128 kbps when the clip has an audio track. A silent clip stays silent |
| Length | Cut at 60 seconds. The row stores at most 60. The edge function allows the container to read up to 61 seconds so encoder rounding of a full minute still uploads |
| Size | At most 22 MB (`23068672` bytes). A minute at 2.5 Mbps plus audio is about 20 MB |
| Poster | One frame, encoded with the photo thumbnail rules |

The composer shows a progress percent, a cancel button, and a retry when the encode fails. Cancelling stops the Transformer. The original camera file is never uploaded. A small source is not scaled up.

The camera capture limit is 60 seconds. Gallery clips are cut to the same length.

## What the server checks

Migration `supabase/migrations/20261022140000_media_budgets.sql`:

- `contribution_media` checks widen to 2048 px, 60 seconds, and 22 MB. Old rows of 1600 px / 30 seconds / 8 MB still satisfy the new checks.
- `create_contribution` uses those same numbers. A photo thumbnail is stored when `thumb_byte_size` is between 1 and 512000. A video without one is rejected.
- Bucket `contribution-media`: `file_size_limit` **23068672**, mime types still jpeg, png, webp, mp4, and webm.
- Bucket `drop-media`: `file_size_limit` **524288**. Mime types stay jpeg, png, and webp.
- `private.can_read_drop_media` also matches a `.thumb.jpg` sibling.

`supabase/functions/store-contribution-media`:

- A photo or a `.thumb.jpg` over 512000 bytes is rejected.
- An MP4 is rejected when it is empty, over 22 MB, or its `mvhd` duration is missing, not positive, or over 61 seconds.
- Images are still stripped of Exif before they are stored.
- PNG and WebM stay in the allow list. **WebM duration is not read from the bytes.** The size cap applies, and the declared duration is checked in SQL. New clips from this app are MP4 only.

`supabase/functions/strip-exif` rejects a body over 512 KB before it stores anything.

Voice-clip limits are not in this migration.

## Apply on the hosted project

Not applied from this repo's agents, and not applied by CI. From a machine that is already linked, after the history repair in `supabase/README.md` if that repair has not been done:

```bash
supabase link --project-ref jpeuldzegtbqfezijhuy
supabase db push
supabase functions deploy store-contribution-media --project-ref jpeuldzegtbqfezijhuy
supabase functions deploy strip-exif --project-ref jpeuldzegtbqfezijhuy
```

Push the database first, then deploy both functions. A new 60-second video needs the wider bucket and the new function together. A drop photo needs the 512 KB bucket and the matching `strip-exif` limit together.

## What this does not change

- Files already in Storage stay where they are and stay readable.
- PNG and WebM uploads from an older build are still accepted.
- A contribution row that has no thumbnail still renders from the full image.
- Voice clips stay 30 seconds and 256 KB.
- Supabase image transformations are not used.
