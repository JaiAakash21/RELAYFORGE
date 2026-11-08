/**
 * The architecture diagram.
 *
 * Inline SVG rather than an image or a Mermaid render. Three reasons that matter
 * here: it inherits `currentColor` so it cannot drift out of step with the
 * palette, it adds no network request to the landing page's critical path, and
 * it is reachable — the `<title>`, `<desc>` and `role="img"` give a screen
 * reader the same information a sighted reader gets from the arrows, which an
 * exported PNG cannot.
 *
 * Laid out on a fixed 1000x420 viewBox and scaled with `w-full h-auto`, so it is
 * crisp at any width without a media query.
 */
export function ArchitectureDiagram() {
  return (
    <div className="rf-card overflow-hidden">
      <div className="overflow-x-auto">
        <svg
          viewBox="0 0 1000 420"
          className="h-auto w-full min-w-[720px]"
          role="img"
          aria-labelledby="arch-title arch-desc"
        >
          <title id="arch-title">RelayForge event delivery architecture</title>
          <desc id="arch-desc">
            A client application calls the RelayForge REST API, which authenticates the
            caller and resolves their tenant. Event ingestion writes the event, one
            delivery row per subscribed endpoint, and one outbox row per delivery inside
            a single PostgreSQL transaction. A separate outbox publisher claims those
            rows and publishes them to Kafka, marking them published only after the
            broker acknowledges. Delivery workers consume from Kafka, take an exclusive
            lease on the delivery row, and make the signed HTTP request to the customer
            endpoint. A 2xx response marks the delivery successful. A 429, a 5xx or a
            timeout schedules a retry with exponential backoff recorded on the delivery
            row, which a dispatcher later re-enqueues. When the attempt budget is
            exhausted the delivery is marked failed and appears in the dead-letter
            queue, from where an operator can replay it.
          </desc>

          <defs>
            <marker
              id="arrow"
              viewBox="0 0 10 10"
              refX="9"
              refY="5"
              markerWidth="6"
              markerHeight="6"
              orient="auto-start-reverse"
            >
              <path d="M 0 0 L 10 5 L 0 10 z" fill="#32323e" />
            </marker>
            <marker
              id="arrow-accent"
              viewBox="0 0 10 10"
              refX="9"
              refY="5"
              markerWidth="6"
              markerHeight="6"
              orient="auto-start-reverse"
            >
              <path d="M 0 0 L 10 5 L 0 10 z" fill="#6366f1" />
            </marker>
          </defs>

          {/* ---- The transactional boundary. Drawn first, as a container, because
                  it is the single most important fact in the diagram. ---- */}
          <rect
            x="232"
            y="96"
            width="236"
            height="148"
            rx="10"
            fill="#1e1b4b"
            fillOpacity="0.28"
            stroke="#6366f1"
            strokeOpacity="0.5"
            strokeDasharray="4 3"
          />
          <text x="350" y="116" textAnchor="middle" fill="#7c7ff5" fontSize="10.5" fontWeight="600">
            ONE POSTGRESQL TRANSACTION
          </text>

          {/* ---- Nodes ---- */}
          <Node x={24} y={158} w={150} h={54} title="Client application" sub="POST /api/v1/events" />
          <Node
            x={24}
            y={238}
            w={150}
            h={44}
            title="API key auth"
            sub="tenant resolved"
            muted
          />

          <Node x={248} y={128} w={204} h={34} title="events" sub="immutable record" small />
          <Node x={248} y={170} w={204} h={34} title="deliveries" sub="one per endpoint" small />
          <Node x={248} y={212} w={204} h={34} title="outbox_events" sub="to be relayed" small />

          <Node
            x={508}
            y={154}
            w={138}
            h={58}
            title="Outbox publisher"
            sub="SKIP LOCKED · waits for ack"
          />
          <Node x={688} y={154} w={104} h={58} title="Kafka" sub="partition = endpoint" accent />
          <Node
            x={508}
            y={272}
            w={138}
            h={58}
            title="Dispatcher"
            sub="due retries · lost messages"
          />

          <Node
            x={834}
            y={154}
            w={142}
            h={58}
            title="Delivery worker"
            sub="lease · sign · send"
          />
          <Node x={834} y={44} w={142} h={52} title="Customer endpoint" sub="HMAC verified" />

          <Node x={688} y={330} w={104} h={48} title="SUCCESS" sub="2xx" success small />
          <Node x={508} y={366} w={138} h={44} title="Dead letter" sub="budget exhausted" failed small />
          <Node x={248} y={330} w={204} h={44} title="Manual replay" sub="history preserved" small />

          {/* ---- Edges ---- */}
          <Edge d="M 174 185 L 232 185" />
          <Edge d="M 99 212 L 99 238" />
          <Edge d="M 468 183 L 508 183" />
          <Edge d="M 646 183 L 688 183" accent />
          <Edge d="M 792 183 L 834 183" accent />
          <Edge d="M 905 154 L 905 96" />
          <text x="915" y="128" fill="#9c9cab" fontSize="10">
            signed POST
          </text>

          {/* 2xx -> success */}
          <Edge d="M 905 212 C 905 280, 780 300, 745 330" success />
          <text x="800" y="296" fill="#2dd47f" fontSize="10">
            2xx
          </text>

          {/* retryable -> dispatcher */}
          <Edge d="M 834 206 C 760 250, 660 262, 646 290" retry />
          <text x="676" y="252" fill="#f0a126" fontSize="10">
            429 / 5xx / timeout
          </text>

          {/* dispatcher -> kafka */}
          <Edge d="M 578 272 L 578 222 L 688 200" accent />
          <text x="560" y="248" fill="#9c9cab" fontSize="10" textAnchor="end">
            re-enqueue
          </text>

          {/* dispatcher reads deliveries */}
          <Edge d="M 508 300 L 452 300 L 452 246" />
          <text x="462" y="316" fill="#6c6c7c" fontSize="9.5">
            next_attempt_at
          </text>

          {/* exhausted -> dead letter */}
          <Edge d="M 578 330 L 578 366" failed />
          <text x="588" y="352" fill="#f2556f" fontSize="10">
            exhausted
          </text>

          {/* dead letter -> replay -> deliveries */}
          <Edge d="M 508 388 L 452 388" failed />
          <Edge d="M 350 330 L 350 246" accent />
          <text x="360" y="296" fill="#7c7ff5" fontSize="10">
            reopened
          </text>

          {/* Legend */}
          <g transform="translate(24, 318)">
            <text x="0" y="0" fill="#6c6c7c" fontSize="9.5" fontWeight="600">
              POSTGRESQL IS THE SOURCE OF TRUTH
            </text>
            <text x="0" y="18" fill="#6c6c7c" fontSize="9.5">
              Kafka decides who does the work.
            </text>
            <text x="0" y="32" fill="#6c6c7c" fontSize="9.5">
              It never decides whether the
            </text>
            <text x="0" y="46" fill="#6c6c7c" fontSize="9.5">
              work still needs doing.
            </text>
          </g>
        </svg>
      </div>
    </div>
  );
}

function Node({
  x,
  y,
  w,
  h,
  title,
  sub,
  accent = false,
  success = false,
  failed = false,
  muted = false,
  small = false,
}: {
  x: number;
  y: number;
  w: number;
  h: number;
  title: string;
  sub?: string;
  accent?: boolean;
  success?: boolean;
  failed?: boolean;
  muted?: boolean;
  small?: boolean;
}) {
  const fill = accent
    ? "#1e1b4b"
    : success
      ? "#0b2e1c"
      : failed
        ? "#30101a"
        : muted
          ? "#131318"
          : "#1a1a21";
  const stroke = accent
    ? "#312e81"
    : success
      ? "#15532f"
      : failed
        ? "#5e1f2e"
        : "#23232c";
  const titleFill = accent
    ? "#7c7ff5"
    : success
      ? "#2dd47f"
      : failed
        ? "#f2556f"
        : "#ededf0";

  return (
    <g>
      <rect x={x} y={y} width={w} height={h} rx="7" fill={fill} stroke={stroke} />
      <text
        x={x + w / 2}
        y={sub ? y + h / 2 - 3 : y + h / 2 + 4}
        textAnchor="middle"
        fill={titleFill}
        fontSize={small ? 11 : 12}
        fontWeight="600"
      >
        {title}
      </text>
      {sub ? (
        <text
          x={x + w / 2}
          y={y + h / 2 + 12}
          textAnchor="middle"
          fill="#6c6c7c"
          fontSize="9.5"
        >
          {sub}
        </text>
      ) : null}
    </g>
  );
}

function Edge({
  d,
  accent = false,
  success = false,
  retry = false,
  failed = false,
}: {
  d: string;
  accent?: boolean;
  success?: boolean;
  retry?: boolean;
  failed?: boolean;
}) {
  const stroke = accent
    ? "#6366f1"
    : success
      ? "#2dd47f"
      : retry
        ? "#f0a126"
        : failed
          ? "#f2556f"
          : "#32323e";
  return (
    <path
      d={d}
      fill="none"
      stroke={stroke}
      strokeWidth="1.5"
      markerEnd={accent ? "url(#arrow-accent)" : "url(#arrow)"}
      opacity={accent || success || retry || failed ? 0.85 : 1}
    />
  );
}
