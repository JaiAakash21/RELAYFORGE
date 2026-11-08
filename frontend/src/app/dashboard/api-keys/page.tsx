import type { Metadata } from "next";
import { PageHeader } from "@/components/ui/PageHeader";
import { Card } from "@/components/ui/Card";
import { ErrorState } from "@/components/ui/States";
import { ApiRequestError, apiGet } from "@/lib/api/server";
import { ApiKeysView } from "./ApiKeysView";
import type { ApiKey } from "@/types/api";

export const metadata: Metadata = { title: "API keys" };

export default async function ApiKeysPage() {
  let keys: ApiKey[];
  try {
    keys = await apiGet<ApiKey[]>("/api/v1/api-keys");
  } catch (error) {
    return (
      <>
        <PageHeader title="API keys" />
        <Card className="mt-6">
          <ErrorState
            title="Could not load API keys"
            message={
              error instanceof ApiRequestError
                ? error.message
                : "The API could not be reached."
            }
            traceId={error instanceof ApiRequestError ? error.payload.traceId : null}
          />
        </Card>
      </>
    );
  }
  return <ApiKeysView initial={keys} />;
}
