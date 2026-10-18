---
status: accepted
date: 2026-10-17
decision-makers: Steven Sills II
---

# Supabase and PostGIS, MapLibre maps, Astro on Cloudflare Pages

## Context and Problem Statement

Drops are geographic. Authorization has to live next to the data. The public site (`xhere.world`) is a landing page, share links, and the legal pages the stores require. It is not the old web app. Which backend, map stack, and web host do that without a paid account on day one?

## Decision Drivers

* Real geography (`ST_DWithin`, clustering) on a free tier.
* Row-Level Security instead of hand-rolled admin checks.
* Auth, storage, and scheduled jobs without a second vendor.
* Map tiles with no API key and no billing account.
* A web host whose free tier allows commercial use.
* Plain Postgres, so the data can leave later.

## Considered Options

* Backend: Supabase; Firebase; Neon plus a custom API on Cloudflare Workers; self-hosted Postgres.
* Maps: MapLibre Native with OpenFreeMap; Google Maps Platform; Protomaps PMTiles on R2.
* Web: Astro on Cloudflare Pages; the old web app; Vercel Hobby.

## Decision Outcome

Chosen option: **Supabase (Postgres + PostGIS + Auth + Storage + Edge Functions + Realtime + pg_cron), maps on MapLibre Native with OpenFreeMap vector tiles, and a small Astro site on Cloudflare Pages**, because that combination is free to start, fits the data model in PLAN §5, and avoids Google Maps billing and Vercel's non-commercial Hobby limit.

### Consequences

* Good, because official clients exist for Kotlin Multiplatform, Swift, and the web.
* Good, because a `pg_dump` is the exit ramp if Supabase stops fitting.
* Bad, because the free Supabase project pauses after inactivity, and creating the account still needs Steven's OK.
* Bad, because OpenFreeMap and Wikipedia/OSM layers require visible attribution (ODbL, CC BY-SA).
