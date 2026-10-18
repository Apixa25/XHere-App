# Contributing

XHere is rebuilt in small milestones. Read [`docs/PLAN.md`](docs/PLAN.md) before changing code. [`docs/PROGRESS.md`](docs/PROGRESS.md) is the log of what already landed.

## Ground rules

These are the rules in PLAN §7. Every milestone follows them.

* Work on a branch, open a PR to `main`, keep CI green, update `docs/PROGRESS.md` with a dated (America/New_York) entry, and link the PR.
* Never commit secrets. Use `.env.example` and GitHub Actions secrets. A gitleaks scan runs in CI (added in M0) over the full git history.
* No paid services and no new accounts without Steven's OK. If blocked on a credential, stub it, document it in the PR, and stop.
* Respect licenses (OSM ODbL, Wikipedia CC BY-SA attribution, OpenFreeMap attribution).
* Each chunk lists **Inputs**, **Deliverables**, and **Acceptance criteria**. Finish one chunk per PR.

Do not commit `.env` files, keystores, `google-services.json`, or anything under `uploads/`. The old repository `Apixa25/XHere-Web` is read-only. Do not push to it.

## CI

Pull requests and pushes to this repo run [`.github/workflows/ci.yml`](.github/workflows/ci.yml) (gitleaks). [`.github/workflows/android.yml`](.github/workflows/android.yml) builds and tests `shared/` (M2) and, since M3, assembles and Robolectric-tests `:android:app` on the Linux runner when Android or Gradle files change. [`.github/workflows/web.yml`](.github/workflows/web.yml) runs `npm run build`, the fixture share-page check, and Lighthouse when `web/` changes (M8).

[`.github/workflows/supabase.yml`](.github/workflows/supabase.yml) runs when `supabase/**` changes. It starts the free local Supabase stack (Supabase CLI + Docker on the GitHub runner, no account) and runs `supabase db reset`, `supabase db lint`, and `supabase test db` (pgTAP). See [`supabase/README.md`](supabase/README.md) to run the same thing locally.
