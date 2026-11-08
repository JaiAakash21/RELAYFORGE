/**
 * Mirrors of the backend's response shapes.
 *
 * Hand-written rather than generated. The API is small and stable enough that a
 * generator would be more machinery than it saves, and writing these by hand
 * forces a decision about which fields the UI actually depends on — a generated
 * client tends to make every field feel equally load-bearing.
 *
 * Nullable fields are typed `| null`, not optional. The backend serialises nulls
 * explicitly because a null is meaningful there (`responseStatus: null` means no
 * HTTP response was ever received), so the type should say "present and null"
 * rather than "might be missing".
 */

export interface ApiError {
  code: string;
  message: string;
  details?: Record<string, string[]>;
  traceId?: string;
  timestamp: string;
}

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
  hasNext: boolean;
}

// ---------------------------------------------------------------- auth

export type TenantRole = "OWNER" | "ADMIN" | "MEMBER" | "VIEWER";

export interface UserSummary {
  id: string;
  email: string;
  displayName: string | null;
  createdAt: string;
}

export interface TenantSummary {
  id: string;
  name: string;
  slug: string;
  role: TenantRole;
}

export interface SessionResponse {
  user: UserSummary;
  tenant: TenantSummary;
  availableTenants: TenantSummary[];
}

// ---------------------------------------------------------------- api keys

export type ApiKeyStatus = "ACTIVE" | "REVOKED" | "EXPIRED";

export interface ApiKey {
  id: string;
  name: string;
  prefix: string;
  maskedKey: string;
  createdAt: string;
  expiresAt: string | null;
  revokedAt: string | null;
  lastUsedAt: string | null;
  status: ApiKeyStatus;
}

export interface CreatedApiKey {
  apiKey: ApiKey;
  /** The full key. Returned once, by the create call, and never again. */
  key: string;
  warning: string;
}

// ---------------------------------------------------------------- endpoints

export type EndpointHealthState =
  | "HEALTHY"
  | "DEGRADED"
  | "FAILING"
  | "NO_DATA"
  | "INSUFFICIENT_DATA";

export interface EndpointHealth {
  succeeded: number;
  failed: number;
  pending: number;
  successRate: number;
  state: EndpointHealthState;
}

export interface WebhookEndpoint {
  id: string;
  name: string;
  url: string;
  description: string | null;
  subscribedEventTypes: string[];
  active: boolean;
  paused: boolean;
  pausedAt: string | null;
  retryMaxAttempts: number | null;
  retryInitialDelayMs: number | null;
  retryMaxDelayMs: number | null;
  retryTimeoutMs: number | null;
  retryJitterFactor: number | null;
  rateLimitPerSecond: number | null;
  secretRotatedAt: string | null;
  hasPreviousSecret: boolean;
  createdAt: string;
  updatedAt: string;
  health: EndpointHealth;
}

export interface EndpointSecret {
  endpointId: string;
  secret: string;
  warning: string;
}

export interface EndpointTestResult {
  delivered: boolean;
  responseStatus: number | null;
  durationMs: number | null;
  errorMessage: string | null;
  errorClass: string;
  eventId: string;
  deliveryId: string | null;
}

// ---------------------------------------------------------------- events

export type EventSource = "API" | "PLAYGROUND" | "ENDPOINT_TEST" | "SEED";

export interface DeliverySummary {
  total: number;
  succeeded: number;
  failed: number;
  pending: number;
  cancelled: number;
}

export interface EventListItem {
  id: string;
  eventType: string;
  idempotencyKey: string | null;
  source: EventSource;
  payloadBytes: number;
  createdAt: string;
  deliverySummary: DeliverySummary;
}

export interface EventDetail {
  id: string;
  eventType: string;
  payload: unknown;
  idempotencyKey: string | null;
  source: EventSource;
  payloadBytes: number;
  createdAt: string;
  deliverySummary: DeliverySummary;
}

export interface AcceptedDelivery {
  deliveryId: string;
  endpointId: string;
  endpointName: string | null;
  status: string;
}

export interface PublishResponse {
  eventId: string;
  eventType: string;
  createdAt: string;
  deduplicated: boolean;
  deliveriesCreated: number;
  deliveries: AcceptedDelivery[];
}

// ---------------------------------------------------------------- deliveries

export type DeliveryStatus =
  | "PENDING"
  | "IN_PROGRESS"
  | "RETRY_SCHEDULED"
  | "SUCCESS"
  | "FAILED"
  | "CANCELLED";

export type AttemptOutcome = "SUCCESS" | "RETRYABLE_FAILURE" | "TERMINAL_FAILURE";

export type AttemptKind = "AUTOMATIC" | "RETRY" | "REPLAY" | "MANUAL_TEST";

export interface Delivery {
  id: string;
  eventId: string;
  endpointId: string;
  endpointName: string | null;
  endpointUrl: string | null;
  eventType: string | null;
  status: DeliveryStatus;
  attemptCount: number;
  maxAttempts: number;
  attemptsRemaining: number;
  replayCount: number;
  nextAttemptAt: string | null;
  firstAttemptAt: string | null;
  completedAt: string | null;
  lastResponseCode: number | null;
  lastError: string | null;
  lastErrorClass: string | null;
  leaseUntil: string | null;
  leaseOwner: string | null;
  replayable: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface DeliveryAttempt {
  id: string;
  attemptNumber: number;
  kind: AttemptKind;
  outcome: AttemptOutcome;
  /** Null when no HTTP response was received — a timeout or transport failure. */
  responseStatus: number | null;
  responseBodyExcerpt: string | null;
  responseHeadersExcerpt: string | null;
  errorMessage: string | null;
  errorClass: string | null;
  retryAfterSeconds: number | null;
  requestBytes: number | null;
  durationMs: number;
  workerId: string | null;
  traceId: string | null;
  startedAt: string;
  completedAt: string;
}

export interface ReplayRecord {
  id: string;
  createdAt: string;
  previousStatus: string;
  attemptsBefore: number;
  reason: string | null;
  requestedBy: string | null;
  batchId: string | null;
}

export interface DeliveryDetail {
  delivery: Delivery;
  eventPayload: unknown;
  attempts: DeliveryAttempt[];
  replays: ReplayRecord[];
}

export interface DeadLetter {
  id: string;
  eventId: string;
  eventType: string | null;
  endpointId: string;
  endpointName: string | null;
  endpointUrl: string | null;
  attemptCount: number;
  replayCount: number;
  lastResponseCode: number | null;
  lastError: string | null;
  lastErrorClass: string | null;
  firstAttemptAt: string | null;
  failedAt: string;
  createdAt: string;
}

export interface ReplayResult {
  deliveryId: string;
  accepted: boolean;
  status: string;
  message: string;
}

export interface BulkReplayResult {
  batchId: string;
  requested: number;
  accepted: number;
  skipped: number;
  results: ReplayResult[];
}

// ---------------------------------------------------------------- metrics

export interface QueueHealth {
  dueBacklog: number;
  inProgress: number;
  oldestDueAgeSeconds: number;
  unpublishedOutboxRecords: number;
  oldestUnpublishedOutboxAgeSeconds: number;
}

export interface MetricsSummary {
  totalEvents: number;
  eventsInWindow: number;
  totalDeliveries: number;
  successfulDeliveries: number;
  failedDeliveries: number;
  pendingDeliveries: number;
  inProgressDeliveries: number;
  deadLetterCount: number;
  successRate: number;
  avgLatencyMs: number;
  p50LatencyMs: number;
  p95LatencyMs: number;
  p99LatencyMs: number;
  attemptsInWindow: number;
  retriesInWindow: number;
  replaysInWindow: number;
  activeEndpoints: number;
  queue: QueueHealth;
  window: string;
  /** True: the percentiles are interpolated from histogram buckets. */
  approximateLatency: boolean;
  generatedAt: string;
}

export interface TimeseriesPoint {
  timestamp: string;
  attempts: number;
  successes: number;
  retryableFailures: number;
  terminalFailures: number;
  avgDurationMs: number;
  maxDurationMs: number;
}

export interface Timeseries {
  window: string;
  bucketMinutes: number;
  points: TimeseriesPoint[];
}

export interface EndpointPerformance {
  endpointId: string;
  name: string;
  url: string;
  succeeded: number;
  failed: number;
  pending: number;
  successRate: number;
  health: EndpointHealthState;
}

// ---------------------------------------------------------------- playground

export interface ScenarioParameter {
  key: string;
  label: string;
  type: string;
  min: number | null;
  max: number | null;
  help: string;
}

export interface ScenarioDefaults {
  failureCount: number;
  statusCode: number;
  delayMs: number;
  retryAfterSeconds: number;
  recoverAfterSeconds: number;
  maxAttempts: number;
}

export interface Scenario {
  id: string;
  name: string;
  description: string;
  /** What this scenario demonstrates about the platform. */
  teaches: string;
  /** What should happen, so a viewer can judge the run rather than just watch it. */
  expectedOutcome: string;
  parameters: ScenarioParameter[];
  defaults: ScenarioDefaults;
}

export interface SimulatorView {
  requestsReceived: number | null;
  failuresReturned: number | null;
  successesReturned: number | null;
}

export interface PlaygroundRun {
  runId: string;
  scenarioId: string;
  scenarioName: string;
  config: Record<string, unknown>;
  status: "RUNNING" | "SUCCEEDED" | "DEAD_LETTERED" | "CANCELLED";
  outcomeNote: string | null;
  endpointId: string | null;
  eventId: string | null;
  deliveryId: string | null;
  simulatorReceivePath: string;
  delivery: Delivery | null;
  attempts: DeliveryAttempt[];
  simulator: SimulatorView;
  secondsUntilNextAttempt: number | null;
  createdAt: string;
  completedAt: string | null;
}

export interface PlaygroundRunSummary {
  runId: string;
  scenarioId: string;
  scenarioName: string;
  status: string;
  deliveryId: string | null;
  createdAt: string;
  completedAt: string | null;
}
