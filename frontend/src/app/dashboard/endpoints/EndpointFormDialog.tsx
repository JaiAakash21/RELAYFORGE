"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { TextInput } from "@/components/ui/Input";
import { InlineError } from "@/components/ui/States";
import { useToast } from "@/components/ui/Toast";
import { ClientApiError, api } from "@/lib/api/client";
import type { EndpointSecret, WebhookEndpoint } from "@/types/api";

/**
 * Create / edit form for an endpoint.
 *
 * <h2>Retry overrides are optional on purpose</h2>
 *
 * Every retry field may be left blank, in which case the endpoint inherits the
 * system default. Pre-filling them with the current defaults would silently
 * freeze those values onto the endpoint, so a later change to the platform
 * default would not reach it — which is the opposite of what someone leaving a
 * field untouched intends.
 */
export function EndpointFormDialog({
  open,
  onClose,
  knownEventTypes,
  existing,
  onCreated,
  onUpdated,
}: {
  open: boolean;
  onClose: () => void;
  knownEventTypes: string[];
  existing?: WebhookEndpoint;
  onCreated?: (secret: EndpointSecret) => void;
  onUpdated?: () => void;
}) {
  const toast = useToast();
  const isEdit = existing !== undefined;

  const [name, setName] = useState(existing?.name ?? "");
  const [url, setUrl] = useState(existing?.url ?? "");
  const [description, setDescription] = useState(existing?.description ?? "");
  const [eventTypes, setEventTypes] = useState(
    existing?.subscribedEventTypes.join(", ") ?? "",
  );
  const [maxAttempts, setMaxAttempts] = useState(
    existing?.retryMaxAttempts?.toString() ?? "",
  );
  const [initialDelay, setInitialDelay] = useState(
    existing?.retryInitialDelayMs?.toString() ?? "",
  );
  const [maxDelay, setMaxDelay] = useState(existing?.retryMaxDelayMs?.toString() ?? "");
  const [timeout, setTimeoutMs] = useState(existing?.retryTimeoutMs?.toString() ?? "");
  const [rateLimit, setRateLimit] = useState(
    existing?.rateLimitPerSecond?.toString() ?? "",
  );

  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);

  function parseEventTypes(): string[] {
    return eventTypes
      .split(",")
      .map((value) => value.trim())
      .filter((value) => value.length > 0);
  }

  function optionalNumber(value: string): number | null {
    if (value.trim() === "") return null;
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    setFieldErrors({});
    setSubmitting(true);

    const body = {
      name: name.trim(),
      url: url.trim(),
      description: description.trim() || null,
      subscribedEventTypes: parseEventTypes(),
      retryMaxAttempts: optionalNumber(maxAttempts),
      retryInitialDelayMs: optionalNumber(initialDelay),
      retryMaxDelayMs: optionalNumber(maxDelay),
      retryTimeoutMs: optionalNumber(timeout),
      retryJitterFactor: null,
      rateLimitPerSecond: optionalNumber(rateLimit),
    };

    try {
      if (isEdit) {
        await api.patch<WebhookEndpoint>(`/endpoints/${existing.id}`, body);
        toast.success("Endpoint updated");
        onUpdated?.();
        onClose();
      } else {
        const secret = await api.post<EndpointSecret>("/endpoints", {
          ...body,
          secret: null,
        });
        onCreated?.(secret);
      }
    } catch (caught) {
      if (caught instanceof ClientApiError) {
        const mapped: Record<string, string> = {};
        for (const [field, messages] of Object.entries(caught.fieldErrors)) {
          if (messages[0]) mapped[field] = messages[0];
        }
        setFieldErrors(mapped);
        // The SSRF policy returns its reason as the message, and it is genuinely
        // useful to the user — "resolves to a private address" tells them what
        // to change. Shown verbatim rather than replaced with a generic error.
        setError(caught.message);
      } else {
        setError("The request could not be completed.");
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog
      open={open}
      onClose={onClose}
      title={isEdit ? "Edit endpoint" : "Add an endpoint"}
      description={
        isEdit
          ? "Changes apply to deliveries created from now on. Deliveries already in flight keep the attempt budget they were created with."
          : "RelayForge validates the destination against its SSRF policy: private, loopback, link-local and cloud-metadata addresses are refused."
      }
      size="lg"
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={submitting}>
            Cancel
          </Button>
          <Button
            variant="primary"
            onClick={submit}
            loading={submitting}
            type="submit"
            form="endpoint-form"
          >
            {isEdit ? "Save changes" : "Create endpoint"}
          </Button>
        </>
      }
    >
      <form id="endpoint-form" onSubmit={submit} className="flex flex-col gap-4" noValidate>
        {error ? <InlineError message={error} /> : null}

        <TextInput
          label="Name"
          required
          value={name}
          onChange={(event) => setName(event.target.value)}
          error={fieldErrors.name}
          placeholder="Fulfilment service (production)"
          maxLength={120}
        />

        <TextInput
          label="Destination URL"
          required
          type="url"
          value={url}
          onChange={(event) => setUrl(event.target.value)}
          error={fieldErrors.url}
          placeholder="https://api.yourservice.com/webhooks/relayforge"
          help="https is required when the prod egress profile is active. Redirects are never followed."
        />

        <TextInput
          label="Description"
          value={description}
          onChange={(event) => setDescription(event.target.value)}
          error={fieldErrors.description}
          placeholder="Optional"
          maxLength={500}
        />

        <TextInput
          label="Subscribed event types"
          value={eventTypes}
          onChange={(event) => setEventTypes(event.target.value)}
          error={fieldErrors.subscribedEventTypes}
          placeholder="order.created, order.*, invoice.paid"
          help={
            knownEventTypes.length > 0
              ? `Comma-separated. Use a trailing wildcard such as order.* , or leave blank to receive every type. Seen so far: ${knownEventTypes.slice(0, 6).join(", ")}`
              : "Comma-separated. Use a trailing wildcard such as order.* , or leave blank to receive every event type."
          }
        />

        <fieldset className="rounded border border-border bg-surface-2 p-3">
          <legend className="rf-label px-1">Retry policy overrides</legend>
          <p className="mb-3 text-xs leading-relaxed text-content-tertiary">
            Leave any field blank to inherit the system default. A value set here is
            snapshotted onto each delivery when it is created, so changing it later
            cannot retroactively dead-letter work already in flight.
          </p>
          <div className="grid gap-3 sm:grid-cols-2">
            <TextInput
              label="Max attempts"
              type="number"
              min={1}
              max={25}
              value={maxAttempts}
              onChange={(event) => setMaxAttempts(event.target.value)}
              error={fieldErrors.retryMaxAttempts}
              placeholder="default"
            />
            <TextInput
              label="Per-attempt timeout (ms)"
              type="number"
              min={100}
              max={120000}
              value={timeout}
              onChange={(event) => setTimeoutMs(event.target.value)}
              error={fieldErrors.retryTimeoutMs}
              placeholder="default"
            />
            <TextInput
              label="Initial delay (ms)"
              type="number"
              min={100}
              max={3600000}
              value={initialDelay}
              onChange={(event) => setInitialDelay(event.target.value)}
              error={fieldErrors.retryInitialDelayMs}
              placeholder="default"
            />
            <TextInput
              label="Max delay (ms)"
              type="number"
              min={100}
              max={86400000}
              value={maxDelay}
              onChange={(event) => setMaxDelay(event.target.value)}
              error={fieldErrors.retryMaxDelayMs}
              placeholder="default"
            />
            <TextInput
              label="Delivery rate limit (per second)"
              type="number"
              min={1}
              max={1000}
              value={rateLimit}
              onChange={(event) => setRateLimit(event.target.value)}
              error={fieldErrors.rateLimitPerSecond}
              placeholder="default"
              help="Protects your receiver. Exceeding it delays deliveries rather than dropping them."
              className="sm:col-span-2"
            />
          </div>
        </fieldset>
      </form>
    </Dialog>
  );
}
