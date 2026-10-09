# ADR 0002 — UI modernization

- **Status:** Proposed — direction for owner critique before any build (2026-10-09)
- **Related:** [ADR 0001](0001-eks-native-architecture.md)

## Context

Upstream's UI is half-migrated: **233 GSP server pages** coexist with **241 Vue 3 components** in
`rundeckapp/grails-spa/packages/ui-trellis` (Vue 3.5, PrimeVue 4.5, Pinia + legacy Vuex,
webpack). Navigation is project-dropdown-centric, the execution log view is a long DOM list, and
the job editor is a long multi-tab form. Users are SREs and platform engineers who live in
GitHub Actions, Argo, Grafana and Linear. Those tools set the expectations.

Upstream conventions are kept (Options API, scoped styles, `library/` vs `app/` component layers,
`$t()` i18n, Jest). Modernization changes the **experience**, not the house style.

## Direction: "calm control room"

Quiet neutral surfaces, one accent color, and loud **semantic** status only where something needs
attention. Dense by default, because operators scan, with a comfortable-density toggle.

### Principles

1. **Runs are the product.** Default home answers "what's running, what failed, what fires next".
   It is not a project list.
2. **Keyboard-first.** `⌘K` command palette over jobs, executions, nodes, projects and actions;
   `g j` / `g e` jump keys; every action reachable without a mouse.
3. **Never lose your place.** URL is state (filters, selected step, log line). Every log line,
   step and execution has a deep link.
4. **Status is never color-only.** Icon + label + color, WCAG 2.2 AA in light and dark.
5. **Fast is a feature.** Skeletons, not spinners. Optimistic enable/disable/run with undo.
   Route-level code splitting.
6. **GitHub is the editor of record** for synced jobs. The UI shows the source commit and offers
   "Edit in GitHub" instead of a form that would fork the truth.

### Key screens (prototype these first)

| Screen | Today | Kestrel |
|---|---|---|
| **Home / Now** | Project list → dashboard | Cross-project live board: Running (with progress), Failed in last 24 h (grouped by job, with failure step), **Upcoming fires** (from CronJob schedules, in viewer TZ), Paused. |
| **Execution** | Flat log + step list | GitHub-Actions-style **step timeline** on the left; virtualized log (100k+ lines) with ANSI color, in-log search, "jump to first error", collapsible per-node sections, sticky step header, copy-link-to-line. |
| **Job editor** | Multi-tab form | Split view: structured form ↔ YAML (Monaco, JSON-Schema validation). **Cron builder** shows the next 5 fire times in the chosen time zone and rejects Quartz-only syntax inline with a plain explanation. Runner-mode picker (pool / isolated) with resource hints. |
| **Activity** | Filter form + table | Faceted filters as chips, saved views, time-range brush over a run-density sparkline. |
| **Navigation** | Top bar + project dropdown | Persistent collapsible left rail (Now, Jobs, Activity, Nodes, Keys, Webhooks, Settings) + project switcher in `⌘K`. |

### Visual system

- **Tokens** as CSS custom properties, implemented as a PrimeVue 4 styled-mode theme preset
  (surface, text, border, accent, and status success/warn/fail/running/queued/aborted), with light,
  dark and system modes.
- **Type:** a humanist UI sans (e.g. Inter) for chrome. Monospace **only** for logs, code, YAML
  and IDs. Not for badges, metadata or pills.
- **Icons:** one consistent SVG icon set across the app (PrimeIcons today, or Lucide). No unicode
  glyphs as icons.
- **Motion:** 120–200 ms, standard easing, disabled under `prefers-reduced-motion`.
- **States:** every list and panel has a designed empty state, error state (with the action that
  fixes it) and loading skeleton.

### Quality gates (measured at CloudFront, not localhost)

- Core Web Vitals p75: LCP < 2.5 s, INP < 200 ms, CLS < 0.1.
- axe-core: zero serious/critical violations on the key screens. Full keyboard pass.
- Log view: 100k lines scrolls at 60 fps on a mid-range laptop.

## Migration strategy

Strangler pattern by traffic: **Execution → Home/Now → Job editor → Activity → the rest**. GSP
pages are retired as their Vue replacements reach parity. The legacy page stays reachable under
`?legacy=1` until removal. Vuex stores move to Pinia as they are touched. Webpack → Vite is
evaluated in M4, not assumed.

## Process

This is a **direction, not a finished design.** Per owner feedback on earlier UX passes, a pass
that is only "validated palette + fixed bugs + screenshots" doesn't meet the bar. The loop is:

1. Direction board (this ADR plus annotated references) → **owner critique**.
2. Clickable prototypes of Home/Now, Execution and Job editor → owner critique → revise.
3. Build behind a feature flag; headless-Playwright screenshot QA in light and dark at 3 widths.
4. Self-assess against the principles above **before** presenting, and iterate again.

## Open questions (owner)

1. Brand: should Kestrel have its own visual identity (logo, accent hue)? Or is it neutral,
   white-label-ready?
2. Is mobile a target (on-call triage: view failures, re-run, abort), or desktop-only?
