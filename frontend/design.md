# EmitKit web — design system

Locked system for every page of the app. Pages share it; they do not vary it. The tokens live in
`src/ui/tokens.css` and are mapped into Tailwind by `src/styles/app.css`. Feature code uses tokens only
(`eslint-rules/no-raw-palette.js` enforces it).

- **Genre:** modern-minimal. **Theme:** Cobalt, adapted for an app. **Macrostructure:** Workbench.
- **Audience:** developers and API teams who manage SDK generation for their OpenAPI specs.
- **Register:** an instrument panel, not a marketing page. Calm, precise, dense, keyboard-first.

## Colour

| Token | Role |
|---|---|
| `bg` | Cool engineered paper behind every page (never pure white or black). |
| `surface` | Panels, tables, inputs, menus and dialogs: one step lighter than `bg` in light, one step lighter in dark. |
| `bg-subtle`, `bg-muted` | Table heads, hover, selected rows, code and log wells. |
| `fg`, `fg-muted`, `fg-subtle` | Ink, secondary text, meta. All reach 4.5:1 on `bg-muted`. |
| `border`, `border-strong`, `border-control` | Hairlines do the structure. `border-control` (3:1) marks interactive edges. |
| `accent` | The one cobalt signal: the primary button, the current nav item, the active tab, links and focus. Under ~5% of a screen. |
| `status-*` | Success, running, failed, awaiting, neutral. Used only for status, always with an icon and a label. |

Dark is graphite at the same hue (≈260), not a separate palette.

## Type

- **Display:** Space Grotesk (h1, h2), roman, `-0.01em` tracking. Page titles `text-2xl`; the run title `text-3xl`.
- **Body/UI:** Inter, 14 px body.
- **Mono:** JetBrains Mono for IDs, SHAs, versions, durations, log lines, code, and the label voice:
  `text-2xs` (11 px) UPPERCASE, `0.06em` tracking, `fg-subtle`. Used for eyebrows, table heads and fact labels.
- Never italic headings. Emphasis is weight or the accent colour.

## Shape, depth, space

- Radius 6 px on controls, 10 px on cards. No pills except the status chip's tinted fill.
- Depth comes from 1 px hairlines. `shadow-raised` (a 1 px lift) on panels and tables; `shadow-overlay` only on
  dialogs, sheets, menus, tooltips and toasts. No glass, gradients, glow or background texture.
- 4 px grid. The content column is capped at `max-w-content` (1200 px) and centred; the spec editor and diffs use the
  full width.

## Shell

- A left rail: the EmitKit wordmark, the org switcher, then icon + label links. The current link takes an accent tick
  and an accent icon.
- A top bar: a bordered ⌘K search affordance that opens the real command palette, then quota, theme and user.
- Below 768 px the rail becomes a drawer.

## Components

- **Buttons:** one solid cobalt primary per view; secondary is a bordered surface; ghost for toolbars. 8 states:
  default, hover, focus-visible, active (1 px press), disabled, loading (spinner, same width), error and success via
  toasts or field errors.
- **Fields:** inside a form they fill their column; in toolbars and filters they size to their content.
- **Panels:** surface + hairline + `shadow-raised`; a mono label or a Space Grotesk h2 as the title.
- **Tables:** surface, mono UPPERCASE heads on `bg-subtle`, 1 px row rules, hover `bg-subtle`.
- **Status pill:** icon + label on a 10% tint of the status colour, hairline in the same tone.
- **Empty states:** dashed hairline, one icon, what is missing, the action that fills it.

## Motion

150 ms `--ease-out` for hover, menus and overlays only. Everything is 0 ms under `prefers-reduced-motion`. No bounce,
no parallax, no autoplay. Spinners only for live work.

## Copy

Declarative and specific: name the run, the step, the version, the command. No hype adjectives, no invented metrics.
