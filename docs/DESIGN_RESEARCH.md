# Design research

## Scope and honesty note

This project was built in a sandboxed environment with restricted outbound
network access. **The reference products' live sites were not browsed during
this work**, so nothing below is presented as a fresh inspection of their
current UI. What follows is the set of interface patterns the design draws on,
from prior familiarity with these tools, stated as design reasoning rather than
as a report of what any site looks like today.

Products whose patterns informed the design: Linear, Vercel, Stripe Dashboard,
Grafana, Svix, Hookdeck.

No branding, copy, asset or code was taken from any of them. The visual
language here is an independent implementation of a common genre — the
dark-first developer instrument panel.

## What the genre gets right, and why

**Dark-first, not dark-as-an-option.** An infrastructure dashboard is looked at
for long stretches, often beside a terminal and an editor that are already
dark. Supporting both themes properly would mean maintaining two sets of status
colours that must be equally legible; one theme done well beats two done
adequately. RelayForge is dark only, and says so.

**Borders, not shadows.** On a dark canvas a drop shadow reads as a smudge.
Separation comes from a 1px hairline and a surface step. Shadows are reserved
for overlays, where they genuinely signal elevation.

**One accent, used sparingly.** A single restrained indigo for primary actions,
the active nav item and focus rings — and nothing else. An accent that appears
everywhere stops meaning anything.

**Density that stays readable.** Delivery tables carry a UUID, a URL, a status,
counters and timestamps. 13px with tabular figures keeps a row scannable;
tabular numerals also stop a counter ticking from 9 to 10 shifting the layout.

**Status by text and colour, never colour alone.** Roughly 8% of men have a
red-green deficiency, and the distinction that matters most here is exactly
success versus failed. Every badge carries its own label, and the palette
varies in lightness as well as hue.

## Decisions taken

| Decision | Reasoning |
|---|---|
| Four surface levels, three border weights, three text weights | A dozen greys invites inconsistency, because nobody remembers whether the card is `#1a1a1f` or `#1c1c21` |
| Semantic colour tokens only (`status-retry`, not `amber-500`) | A component never needs to know which colour it is using, and a palette change is one file |
| Navigation grouped Monitor / Demonstrate / Configure | Grouped by the question a user is answering. A flat list of nine makes the Playground as prominent as Settings, which is backwards |
| Vertical attempt timeline with the **gap** labelled | The gap between attempts is where the retry policy becomes visible — doubling, jitter, `Retry-After` overriding the curve. A table of attempts would hide it |
| "No response" rather than an empty cell for a null status | A timeout producing no HTTP status is a fact. A blank cell reads as missing data |
| Approximate percentiles labelled wherever they appear | They are interpolated from histogram buckets. Presenting them as precise would be a small lie that undermines every other number |
| The landing-page preview marked "Sample data" | A marketing screenshot is where a project is most tempted to invent impressive figures. The real dashboard is one click away and shows zeros for a new account |

## Deliberately avoided

- **Gradient-heavy hero sections and glow effects.** One soft radial behind the
  headline, at 7% opacity. The genre's failure mode is a landing page that
  looks like a crypto product.
- **Animated counters and "live" tickers** that are not reading live data.
- **Sparklines or percentage-change arrows on stat tiles.** Both need a
  comparison baseline the API does not provide; inventing one would present a
  derived number as a measured one.
- **Skeleton screens that do not match their content.** The loading state
  mirrors the stat row and table it replaces, so navigation does not reflow.
- **A hamburger menu on desktop.** Hiding primary navigation to look minimal
  costs a click on every single action.

## Accessibility

Treated as part of the design, not a later pass:

- Skip link as the first tab stop on every page
- `:focus-visible` only, so a mouse click leaves no ring — the usual reason
  people disable focus styles and break keyboard access entirely
- Native `<dialog>`, so focus trapping, inertness and Escape come from the
  browser rather than from hand-rolled code that usually gets one of them wrong
- `aria-live` toasts; errors are `assertive` and do not auto-dismiss, because
  an error that vanishes before it is read loses the only explanation available
- Labelled form controls with `aria-describedby` and `aria-invalid` wired up by
  the field component, so no individual form can forget
- Zoom left unrestricted — capping `maximum-scale` fails WCAG 1.4.4 and is the
  most common accessibility mistake in a polished dashboard
- `prefers-reduced-motion` honoured; the live indicators are decorative, the
  data beside them is not

Verified in a browser: zero horizontal overflow on twelve pages at 390, 768 and
1440 px, and the mobile drawer is reachable and closes on navigation.
