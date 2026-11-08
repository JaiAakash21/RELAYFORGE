#!/usr/bin/env bash
#
# Walks the whole pipeline from the command line: register, create an API key
# and an endpoint, publish an event, watch it retry against a deliberately
# failing receiver, let it dead-letter, then fix the receiver and replay it.
#
# Everything it prints is read back from the API. Nothing is simulated.
#
#   ./infra/scripts/demo.sh
#   API=http://localhost:8080 SIM=http://localhost:8081 ./infra/scripts/demo.sh
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RENDER="$SCRIPT_DIR/render.py"

API="${API:-http://localhost:8080}"
SIM="${SIM:-http://localhost:8081}"
SUFFIX="$(date +%s)"
EMAIL="demo-${SUFFIX}@relayforge.dev"
PASSWORD="a-sufficiently-long-demo-password"

# --- small helpers -----------------------------------------------------------
bold() { printf '\033[1m%s\033[0m\n' "$*"; }
dim()  { printf '\033[2m%s\033[0m\n' "$*"; }
step() { printf '\n\033[1;34m==>\033[0m \033[1m%s\033[0m\n' "$*"; }
fail() { printf '\033[31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# jq is not assumed: a demo script that fails on a missing dependency is a bad
# first impression. python3 ships with every platform this runs on.
json() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }

require() {
  curl -fsS -m 5 "$1" >/dev/null 2>&1 || fail "$2 is not reachable at $1. Run: docker compose up"
}

step "Checking the stack"
require "$API/actuator/health" "The RelayForge API"
require "$SIM/actuator/health" "The webhook simulator"
dim "API        $API"
dim "Simulator  $SIM"

# --- 1. account --------------------------------------------------------------
step "1. Registering an account"
TOKEN=$(curl -fsS -X POST "$API/api/v1/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\",\"organizationName\":\"Demo ${SUFFIX}\"}" \
  | json 'd["accessToken"]')
dim "email: $EMAIL"
dim "password: $PASSWORD"

auth=(-H "Authorization: Bearer $TOKEN")

# --- 2. api key --------------------------------------------------------------
step "2. Creating an API key"
APIKEY=$(curl -fsS -X POST "$API/api/v1/api-keys" "${auth[@]}" \
  -H 'Content-Type: application/json' -d '{"name":"demo key"}' \
  | json 'd["key"]')
dim "key: ${APIKEY:0:20}…  (shown once by the API; stored only as a hash)"

# --- 3. a receiver that fails -------------------------------------------------
step "3. Configuring the simulator to fail permanently"
TARGET="demo${SUFFIX}"
curl -fsS -X PUT "$SIM/simulator/targets/$TARGET" \
  -H 'Content-Type: application/json' \
  -d '{"config":{"type":"PERMANENT_FAILURE","failureCount":0,"statusCode":503,"delayMs":0,"retryAfterSeconds":0,"recoverAfterSeconds":0}}' \
  >/dev/null
dim "target $TARGET will answer 503 to everything"

step "4. Registering an endpoint pointing at it"
EP=$(curl -fsS -X POST "$API/api/v1/endpoints" "${auth[@]}" \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"Demo receiver\",\"url\":\"$SIM/simulator/receive/$TARGET\",\"subscribedEventTypes\":[\"order.*\"],\"retryMaxAttempts\":3,\"retryInitialDelayMs\":2000,\"retryMaxDelayMs\":6000}")
EPID=$(echo "$EP" | json 'd["endpointId"]')
SECRET=$(echo "$EP" | json 'd["secret"]')
dim "endpoint $EPID"
dim "signing secret: ${SECRET:0:18}…  (encrypted at rest; shown once)"

# --- 5. publish ---------------------------------------------------------------
step "5. Publishing an event"
PUB=$(curl -fsS -X POST "$API/api/v1/events" \
  -H "X-API-Key: $APIKEY" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: demo-${SUFFIX}" \
  -d '{"eventType":"order.created","payload":{"orderId":"ORD-1001","amount":2499,"currency":"INR"}}')
DELIVERY=$(echo "$PUB" | json 'd["deliveries"][0]["deliveryId"]')
dim "event    $(echo "$PUB" | json 'd["eventId"]')"
dim "delivery $DELIVERY"
dim "The API returned as soon as the transaction committed — it did not wait"
dim "for the webhook."

# --- 6. idempotency -----------------------------------------------------------
step "6. Repeating the same call with the same Idempotency-Key"
DUP=$(curl -fsS -X POST "$API/api/v1/events" \
  -H "X-API-Key: $APIKEY" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: demo-${SUFFIX}" \
  -d '{"eventType":"order.created","payload":{"orderId":"ORD-1001"}}')
dim "deduplicated: $(echo "$DUP" | json 'str(d["deduplicated"]).lower()')  (the original event id was returned; no second event)"

# --- 7. watch it fail ---------------------------------------------------------
step "7. Watching the retry engine (attempt budget 3, 503 every time)"
for _ in $(seq 1 30); do
  STATUS=$(curl -fsS "$API/api/v1/deliveries/$DELIVERY" "${auth[@]}" | json 'd["delivery"]["status"]')
  ATTEMPTS=$(curl -fsS "$API/api/v1/deliveries/$DELIVERY" "${auth[@]}" | json 'str(d["delivery"]["attemptCount"])+"/"+str(d["delivery"]["maxAttempts"])')
  printf '    status=%-16s attempts=%s\n' "$STATUS" "$ATTEMPTS"
  [[ "$STATUS" == "FAILED" || "$STATUS" == "SUCCESS" ]] && break
  sleep 2
done

step "8. The recorded attempt history"
curl -fsS "$API/api/v1/deliveries/$DELIVERY" "${auth[@]}" | python3 "$RENDER" attempts

# --- 9. dead letter -----------------------------------------------------------
step "9. The dead-letter queue"
curl -fsS "$API/api/v1/dead-letters" "${auth[@]}" | python3 "$RENDER" dead-letters

# --- 10. fix and replay -------------------------------------------------------
step "10. Fixing the receiver and replaying"
curl -fsS -X PUT "$SIM/simulator/targets/$TARGET" \
  -H 'Content-Type: application/json' \
  -d '{"config":{"type":"SUCCESS","failureCount":0,"statusCode":200,"delayMs":0,"retryAfterSeconds":0,"recoverAfterSeconds":0}}' >/dev/null
dim "the simulator now answers 200"

curl -fsS -X POST "$API/api/v1/dead-letters/$DELIVERY/replay" "${auth[@]}" \
  -H 'Content-Type: application/json' -d '{"reason":"Receiver was fixed"}' \
  | json 'd["message"]' | sed 's/^/    /'

for _ in $(seq 1 20); do
  STATUS=$(curl -fsS "$API/api/v1/deliveries/$DELIVERY" "${auth[@]}" | json 'd["delivery"]["status"]')
  [[ "$STATUS" == "SUCCESS" ]] && break
  sleep 2
done

step "11. History after the replay"
curl -fsS "$API/api/v1/deliveries/$DELIVERY" "${auth[@]}" | python3 "$RENDER" after-replay

step "Done"
cat <<EOF
    Sign in to the dashboard at http://localhost:3000

      email     $EMAIL
      password  $PASSWORD

    Everything above is visible there too: the delivery timeline, the
    dead-letter queue and the replay audit record.
EOF
