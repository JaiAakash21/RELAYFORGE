# Screenshots

Captured with Playwright against the running stack. Every figure visible is
real data produced by the pipeline during that session — nothing is mocked.

| File | Screen |
|---|---|
| `01-landing.jpg` | Landing page with the inline SVG architecture diagram |
| `02-register.jpg` | Registration, with live password requirements |
| `03-overview-empty.jpg` | Overview for a new tenant — zeros, because that is the truth |
| `04-api-key-created.jpg` | One-time key reveal |
| `05-playground-explainer.jpg` | Scenario selected, expected outcome stated before the run |
| `06-playground-run-complete.jpg` | **A completed run: HTTP 500, 500, 200 with gaps of +6.7s then +11.0s** |
| `07-deliveries.jpg` | Delivery list |
| `08-delivery-detail.jpg` | Vertical attempt timeline |
| `09-events.jpg` | Event history with per-event fan-out |
| `10-endpoints.jpg` | Endpoints with 24-hour health |
| `11-dead-letters.jpg` | Dead-letter queue |
| `12-observability.jpg` | Throughput, latency, pipeline depth |
| `13-settings.jpg` | Settings and credential handling |
| `14-mobile-landing.jpg` | Landing at 390px |
| `15-mobile-overview.jpg` | Dashboard at 390px |
| `16-mobile-drawer.jpg` | Mobile navigation drawer |

`06-playground-run-complete.jpg` is the one to look at. It shows two failed
attempts and a success, the gap between them roughly doubling, and the
simulator's independent counters (3 requests, 2 failed, 1 ok) agreeing with
RelayForge's own recorded attempt history.
