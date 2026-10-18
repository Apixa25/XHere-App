export const siteCss = `
:root {
  color-scheme: light;
  --ink: #14221c;
  --muted: #3e524a;
  --paper: #f4f7f5;
  --card: #ffffff;
  --line: #d5e0da;
  --lagoon: #0f6e56;
  --lagoon-deep: #053528;
  --amber-soft: #ffe8c2;
  --amber-ink: #3d2a04;
  --focus: #c47b12;
}
* { box-sizing: border-box; }
html { scroll-behavior: auto; }
body {
  margin: 0;
  background: var(--paper);
  color: var(--ink);
  font-family: ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif;
  font-size: 1.0625rem;
  line-height: 1.55;
}
img { max-width: 100%; height: auto; }
a { color: var(--lagoon-deep); }
a:hover { color: var(--lagoon); }
:focus-visible {
  outline: 3px solid var(--focus);
  outline-offset: 3px;
}
.skip {
  position: absolute;
  left: 1rem;
  top: -5rem;
  background: #fff;
  color: var(--ink);
  padding: 0.7rem 1rem;
  z-index: 5;
  border-radius: 8px;
}
.skip:focus { top: 1rem; }
.site-header, .site-footer, .wrap { width: min(68rem, calc(100% - 2rem)); margin-inline: auto; }
.site-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding: 0.5rem 0;
}
.wordmark {
  color: var(--lagoon-deep);
  font-weight: 760;
  font-size: 1.35rem;
  letter-spacing: -0.03em;
  text-decoration: none;
  min-height: 48px;
  display: inline-flex;
  align-items: center;
}
.nav, .footer-nav { display: flex; flex-wrap: wrap; gap: 0.15rem; }
.nav a, .footer-nav a, .text-link {
  display: inline-flex;
  align-items: center;
  min-height: 48px;
  padding: 0.4rem 0.75rem;
  text-decoration: underline;
  text-underline-offset: 0.18em;
}
.nav a[aria-current="page"] { font-weight: 700; }
main { padding-bottom: 3rem; }
h1, h2, h3 { line-height: 1.15; letter-spacing: -0.03em; margin: 0 0 0.6rem; }
h1 { font-size: clamp(2.1rem, 6vw, 4.2rem); }
h2 { font-size: clamp(1.4rem, 3vw, 1.8rem); margin-top: 2rem; }
h3 { font-size: 1.15rem; margin-top: 1.4rem; }
p { margin: 0 0 0.9rem; }
.lede { font-size: 1.25rem; max-width: 38rem; }
.hero {
  padding: 1.5rem 0 1rem;
}
.hero h1 { max-width: 14ch; }
.kicker {
  font-weight: 700;
  color: var(--lagoon);
  letter-spacing: 0.04em;
  text-transform: uppercase;
  font-size: 0.82rem;
  margin-bottom: 0.6rem;
}
.steps, .groups, .split { display: grid; gap: 0.9rem; }
.card {
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 18px;
  padding: 1.1rem 1.15rem 1.2rem;
}
.card h2, .card h3 { margin-top: 0; }
.step-no {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 2rem;
  height: 2rem;
  border-radius: 999px;
  background: #d5f5e8;
  color: var(--lagoon-deep);
  font-weight: 700;
  margin-bottom: 0.6rem;
}
.button, button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 48px;
  padding: 0.7rem 1.2rem;
  background: var(--lagoon-deep);
  color: #fff;
  text-decoration: none;
  border: 0;
  border-radius: 999px;
  font: inherit;
  font-weight: 700;
  cursor: pointer;
}
.button:hover, button:hover { background: var(--lagoon); color: #fff; }
.actions { display: flex; flex-wrap: wrap; align-items: center; gap: 0.4rem 0.8rem; margin: 1rem 0; }
.field { margin: 0 0 0.9rem; }
label { display: block; font-weight: 700; margin-bottom: 0.3rem; }
input {
  width: 100%;
  min-height: 48px;
  padding: 0.55rem 0.75rem;
  border: 1px solid #6e7f78;
  border-radius: 10px;
  background: #fff;
  color: var(--ink);
  font: inherit;
}
.note, .meta, .caption, .site-footer { color: var(--muted); }
.caption { font-size: 0.95rem; }
.draft {
  background: var(--amber-soft);
  color: var(--amber-ink);
  border-radius: 12px;
  padding: 0.8rem 1rem;
  margin: 0.5rem 0 1.2rem;
}
.site-footer { padding: 1rem 0 2.5rem; font-size: 0.98rem; }
.share-card h1 { font-size: clamp(1.8rem, 4vw, 2.8rem); }
.eyebrow { font-weight: 700; color: var(--lagoon); margin-bottom: 0.35rem; }
.lock {
  background: #fff;
  border-left: 4px solid var(--lagoon);
  padding: 0.2rem 0 0.2rem 0.9rem;
}
.badge {
  display: inline-block;
  background: #d5f5e8;
  color: var(--lagoon-deep);
  font-weight: 700;
  border-radius: 999px;
  padding: 0.2rem 0.7rem;
}
.map {
  display: block;
  width: 100%;
  height: auto;
  aspect-ratio: 1200 / 630;
  background: #f2f3f0;
  border-radius: 16px;
  border: 1px solid var(--line);
  margin: 1rem 0 0.5rem;
}
.group-icon { font-style: normal; margin-right: 0.35rem; }
@media (min-width: 800px) {
  .steps { grid-template-columns: repeat(3, 1fr); }
  .groups { grid-template-columns: repeat(2, 1fr); }
  .split { grid-template-columns: 1.2fr 0.8fr; align-items: start; }
}
@media (min-width: 1040px) {
  .groups { grid-template-columns: repeat(3, 1fr); }
}
`;
