# quarkus-ai-governed-mcp

A **governed MCP (Model Context Protocol) platform** built with Quarkus: an MCP tool server exposing an
asylum-applications domain to AI agents, placed behind [agentgateway](https://agentgateway.dev/) for
authentication and guardrails, with end-to-end distributed tracing.

---

## Architecture

```text
┌─────────────┐    MCP/JSON-RPC over HTTP     ┌────────────────┐
│ Goose /     │ ────────────────────────────► │  agentgateway  │  :3000  (admin :15000)
│ MCP client  │   Authorization: Bearer <jwt> │                │
└─────────────┘                               └───────┬────────┘
                                                      │
                     ┌────────────────────────────────┼────────────────────────┐
                     │                                │                        │
              validates JWT                    ExtMCP gRPC                 proxies
                     │                                │                        │
                     ▼                                ▼                        ▼
              ┌────────────┐                 ┌──────────────────┐     ┌──────────────────┐
              │  Keycloak  │ :8081           │ mcp-guardrails   │     │ mcp-applications │
              │ realm: mcp │                 │ ExtMcp  :9001    │     │ /mcp      :8080  │
              └────────────┘                 └──────────────────┘     └────────┬─────────┘
                                                                               │
                                                        ┌──────────────┐  ┌────▼─────┐
                            all spans ────────────────► │    Jaeger    │  │ Postgres │
                                               :4317/:4318, UI :16686   │  │  :5432   │
                                                        └──────────────┘  └──────────┘
```

Nothing reaches the tool server except through the gateway. Identity is verified at the gateway, payloads are
screened by the guardrail service, and every hop emits a span under one trace ID.

### Modules

This is a Maven multi-module build. The root `pom.xml` is a parent/aggregator (`packaging: pom`) holding the
shared properties, Quarkus BOM imports, compiler/surefire/failsafe configuration, and the `native` profile.

| Module | Role | Listens on |
| --- | --- | --- |
| [quarkus-ai-mcp-applications](quarkus-ai-mcp-applications/) | MCP tool server — `@Tool` methods over Panache/PostgreSQL | HTTP `8080`, MCP at `/mcp` |
| [quarkus-ai-mcp-guardrails](quarkus-ai-mcp-guardrails/) | ExtMCP guardrail — gRPC service agentgateway calls to screen traffic | gRPC `9001` |

Supporting configuration at the repository root:

| Path | Purpose |
| --- | --- |
| [podman/compose.yml](podman/compose.yml) | PostgreSQL, Keycloak and Jaeger |
| [agentgateway/config.yaml](agentgateway/config.yaml) | Gateway config — Keycloak JWT auth, guardrails, tracing |
| [goose/config.yaml](goose/config.yaml) | Goose client extension, pointed at the gateway |

### Port map

| Port | Service |
| --- | --- |
| `3000` | agentgateway MCP listener — **the only entry point clients should use** |
| `15000` | agentgateway admin UI |
| `8080` | `quarkus-ai-mcp-applications` (MCP at `/mcp`) |
| `9001` | `quarkus-ai-mcp-guardrails` (ExtMCP gRPC) |
| `8081` | Keycloak (container port 8080) |
| `5432` | PostgreSQL |
| `4317` / `4318` / `16686` | Jaeger OTLP gRPC / OTLP HTTP / UI |

---

## Part 1 — Governed MCP tools

The tool server exposes the asylum-applications domain as MCP tools. Each `@Tool` method becomes a JSON-RPC
operation with an auto-generated JSON schema; `@ToolArg` descriptions are what the LLM reads to decide how to
call it.

| Tool | Effect | Argument constraints |
| --- | --- | --- |
| `list_applications` | All applications | — |
| `get_application_by_id` | One application | `@NotNull Long id` |
| `get_applications_by_country` | Filter by country | `@NotNull @Size(max = 20)` |
| `get_applications_by_country_and_status` | Filter by country + status | `@NotNull @Size(max = 20)`, `@NotNull ApplicationStatus` |
| `add_application` | **Writes** a new application | `@NotNull Application` |

Source: [ApplicationsMcpTools.java](quarkus-ai-mcp-applications/src/main/java/me/amiralles/applications/boundary/ApplicationsMcpTools.java),
backed by [Application.java](quarkus-ai-mcp-applications/src/main/java/me/amiralles/applications/entity/Application.java).

### Why validation at the MCP boundary matters

An LLM composes tool arguments from natural language. It can hallucinate them, and a prompt-injected agent can
be steered into composing hostile ones. The `quarkus-mcp-server-hibernate-validator` extension runs Jakarta
Bean Validation on every argument *before* the method body executes, so a malformed `countryName` is rejected
with a JSON-RPC error rather than reaching Panache.

This is the innermost ring of defence and it is the one that cannot be bypassed — it holds even if the gateway
is misconfigured or something talks to `:8080` directly. Treat the constraints as a contract, not decoration:
`@Size(max = 20)` on a country name is what stops an unbounded string from reaching the query layer.

Note that `add_application` is a **mutating** tool. Exposing a write operation to an autonomous agent is
exactly the case where gateway-side authorization (below) stops being optional.

---

## Part 2 — Security

### What agentgateway buys you

Part 1 leaves a gap: anything that can reach `:8080` can call any tool, including `add_application`. The
obvious fix — building auth into the Quarkus service — scales badly and mixes governance into business logic.

agentgateway is a Rust data-plane proxy that **speaks MCP natively**. That distinction is the whole point. A
generic HTTP proxy sees one opaque `POST /mcp` and can only make all-or-nothing decisions. agentgateway parses
the JSON-RPC envelope, so it can reason about *which tool* is being called with *which arguments*, and it can
rewrite `tools/list` responses so a caller never even learns that tools it may not use exist.

![agentgateway control plane: a Goose client connects over MCP on port 3000; requests pass through JWT
authentication, then CEL tool authorization, then ExtMCP guardrails, before reaching the Quarkus MCP server
on port 8080, with the guardrail service called separately over gRPC on port 9001](assets/agentgateway.png)

*Target architecture. The backend here is `applications-tools` rather than the workshop's `customer-tools`,
and the middle stage — CEL tool authorization — is the
[one layer this repo has not implemented yet](#known-gap-no-tool-level-rbac-yet).*

Concretely, it gives you:

- **A single enforcement point.** One config governs every tool. Add a tool to the Quarkus service and it
  inherits the policy automatically.
- **No governance code in the service.** The Quarkus modules contain zero auth logic. Security is
  configuration, reviewable in isolation and changeable without a redeploy.
- **Policy at tool granularity**, not URL granularity — CEL rules over `mcp.tool.name` and JWT claims.
- **A pluggable inspection hook** (ExtMCP) for logic too specific to express in config.
- **Protocol-aware observability** — spans tagged with MCP method and tool name, not just HTTP status.

### Identity: Keycloak + JWT

[agentgateway/config.yaml](agentgateway/config.yaml) delegates identity to Keycloak:

```yaml
mcpAuthentication:
  mode: strict
  issuer: http://localhost:8081/realms/mcp
  audiences:
  - agentgateway
  provider:
    keycloak: {}
  resourceMetadata:
    resource: http://localhost:3000/mcp
    scopesSupported:
    - openid
    bearerMethodsSupported:
    - header
```

- `mode: strict` — every request must carry a valid bearer token.
- `provider: keycloak: {}` — tells agentgateway to fetch signing keys from Keycloak's endpoint layout.
- `resourceMetadata` — published at `/.well-known/oauth-protected-resource`, so an MCP client can discover
  how to authenticate and run the OAuth flow itself.

Unauthenticated requests get `401` with a `WWW-Authenticate` header pointing at that metadata document.

#### One-time Keycloak setup

The compose file starts a bare Keycloak; the realm and client must be created once. All `kcadm` calls run
inside the container, where Keycloak listens on `8080`:

```shell script
KC="podman exec keycloak /opt/keycloak/bin/kcadm.sh"

$KC config credentials --server http://localhost:8080 \
  --realm master --user admin --password admin

$KC create realms -s realm=mcp -s enabled=true -s sslRequired=NONE

$KC create clients -r mcp \
  -s clientId=agentgateway \
  -s publicClient=true \
  -s standardFlowEnabled=true \
  -s directAccessGrantsEnabled=true \
  -s 'redirectUris=["http://localhost:*","http://127.0.0.1:*"]'
```

**The audience mapper is not optional.** Keycloak defaults the `aud` claim to `account`, which will not match
the `audiences: [agentgateway]` in the gateway config, and every token will be rejected:

```shell script
CLIENT_UUID=$(podman exec keycloak /opt/keycloak/bin/kcadm.sh \
  get clients -r mcp -q clientId=agentgateway --fields id --format csv --noquotes)

$KC create "clients/${CLIENT_UUID}/protocol-mappers/models" -r mcp \
  -s name=agentgateway-audience \
  -s protocol=openid-connect \
  -s protocolMapper=oidc-audience-mapper \
  -s 'config."included.client.audience"=agentgateway' \
  -s 'config."access.token.claim"=true'
```

Finally a test user:

```shell script
$KC create users -r mcp -s username=mcpuser -s enabled=true
$KC set-password -r mcp --username mcpuser --new-password mcppassword
```

> Request tokens via `localhost:8081`, never the container port. Keycloak derives the `iss` claim from the
> request host, so a token fetched on another host/port will not match the configured issuer.

### Guardrails: why MCP needs them

Authentication answers *who is calling*. Authorization answers *what they may call*. Neither inspects **what
is inside the call** — and with MCP that is where the interesting attacks live.

MCP is an LLM-driven protocol, which inverts the usual trust model in two directions:

- **Arguments are attacker-influenceable.** Tool arguments are synthesized by a model from text that may
  include untrusted content — a web page, a ticket, a user message. A successful prompt injection turns the
  agent into a confused deputy holding a perfectly valid JWT. Authentication cannot help: the caller *is* who
  they claim to be, and is being manipulated.
- **Tool descriptions are an attack surface.** `tools/list` responses are fed straight into the model's
  context. A compromised or malicious upstream tool server can return a description carrying instructions to
  the agent — **tool poisoning**. The payload never touches your code; it goes directly into the reasoning loop.

[ExtMcpGuardrailService.java](quarkus-ai-mcp-guardrails/src/main/java/me/amiralles/guardrails/ExtMcpGuardrailService.java)
is a Quarkus gRPC service implementing agentgateway's `ExtMcp` contract
([extmcp.proto](quarkus-ai-mcp-guardrails/src/main/proto/extmcp.proto)) and screens both directions:

```yaml
mcpGuardrails:
  processors:
    - kind: remote
      host: "localhost:9001"
      failureMode: failClosed
      methods:
        tools/call: request      # inspect before forwarding
        tools/list: response     # inspect/rewrite after the backend replies
```

`checkRequest` — on `tools/call` only:

1. Scans `x-mcp-*`, `x-forwarded-*` and `x-real-ip` headers for **CRLF injection** and caps them at 256 bytes.
2. Scans the request payload for blocked patterns: `__proto__`, `constructor`, `../`, `eval(`, `exec(`,
   `<script` — covering prototype pollution, path traversal and script injection.
3. Returns `Pass`, a `mutated` payload, or an `AuthorizationError` with `PERMISSION_DENIED`.

`checkResponse` — on `tools/list`, prefixes every tool description with `[guardrail-verified]`. In this repo
it is a visible marker proving responses really traverse the guardrail; it is also the hook where you would
strip or reject descriptions that try to inject instructions into the agent.

**`failureMode: failClosed` is the critical setting.** If the guardrail service is down, the gateway denies
tool calls rather than waving them through. A security control that silently disables itself on failure is
not a security control — but note the operational consequence: the guardrail module is a hard dependency of
the platform, so start it before the gateway.

### Defence in depth

| Threat | Caught by |
| --- | --- |
| Anonymous access | Keycloak JWT validation at the gateway |
| Expired / forged / wrong-audience token | JWKS signature + `iss`/`aud`/`exp` checks |
| Prompt-injected agent calling a mutating tool | **Gap — see below** |
| CRLF header injection | `checkRequest` header sanitization |
| Prototype pollution, path traversal, script injection in arguments | `checkRequest` pattern scan |
| Tool poisoning via descriptions | `checkResponse` on `tools/list` |
| Malformed or oversized arguments | Bean Validation in the Quarkus tool server |

> ### Known gap: no tool-level RBAC yet
>
> [config.yaml](agentgateway/config.yaml) implements two of the workshop's three layers —
> authentication and guardrails. There is no `mcpAuthorization` block, so **any authenticated user can call
> any tool, including the mutating `add_application`.** Closing it means adding CEL rules, evaluated in order,
> first match wins:
>
> ```yaml
> mcpAuthorization:
>   rules:
>     # operators: full access, including writes
>     - 'has(jwt.realm_access.roles) && "operator" in jwt.realm_access.roles'
>     # viewers: read-only
>     - >
>       has(jwt.realm_access.roles) && "viewer" in jwt.realm_access.roles &&
>       mcp.tool.name in ["list_applications", "get_application_by_id",
>                         "get_applications_by_country",
>                         "get_applications_by_country_and_status"]
> ```
>
> This also requires creating the `operator` / `viewer` realm roles in Keycloak and assigning them. Check the
> claim path against a decoded token first — Keycloak nests realm roles under `realm_access.roles`, which is
> not the flat `jwt.roles` the workshop assumes.

---

## Part 3 — Observability

A single agent prompt fans out into several MCP round trips across three processes. In plain access logs those
are unrelated HTTP records. Distributed tracing stitches them into one waterfall, which is what makes it
possible to answer *which agent did what, in what order, and how long each governed hop took* — the forensic
trail an incident review or a SOC 2 CC7 audit actually needs.

Both the gateway and the tool server export spans to Jaeger over W3C Trace Context.

**agentgateway** ([config.yaml](agentgateway/config.yaml)) — OTLP/gRPC to `4317`:

```yaml
config:
  adminAddr: localhost:15000
  tracing:
    otlpEndpoint: http://localhost:4317
    otlpProtocol: grpc
    randomSampling: 1.0
```

**quarkus-ai-mcp-applications** ([application.properties](quarkus-ai-mcp-applications/src/main/resources/application.properties))
— OTLP/HTTP to `4318`:

```properties
quarkus.otel.service.name=applications-tools
quarkus.otel.exporter.otlp.traces.endpoint=http://localhost:4318
quarkus.otel.exporter.otlp.traces.protocol=http/protobuf
quarkus.otel.traces.sampler=always_on
quarkus.otel.traces.suppress-non-application-uris=false
```

`suppress-non-application-uris=false` is what makes `/mcp` itself appear as a span — without it the MCP
endpoint is filtered out as framework noise and the trace has a hole exactly where you need it.

The log format also carries the trace context, so logs and traces correlate:

```text
%d{HH:mm:ss} %-5p traceId=%X{traceId}, parentId=%X{parentId}, spanId=%X{spanId}, sampled=%X{sampled} ...
```

### How propagation works

1. agentgateway opens a root span when the MCP request arrives.
2. It injects a `traceparent` header into the request it forwards to `:8080`.
3. Quarkus reads `traceparent` and opens a child HTTP server span for `POST /mcp` under the same trace ID,
   with no code changes.
4. Both export to Jaeger; the UI reassembles them.

![Trace propagation: a Goose client sends MCP requests carrying a traceparent header to agentgateway, which
forwards MCP plus traceparent to the Quarkus MCP server; Quarkus exports spans to Jaeger over OTLP HTTP while
agentgateway exports over OTLP gRPC, both into the Jaeger collector on port 4317 with its UI on port
16686](assets/observability.png)

Note that step 3 gives you one span per request, not one per tool. Quarkus OpenTelemetry does not
automatically instrument arbitrary CDI methods, so the `@Tool` method itself does not appear in the trace. If
you want per-tool latency, annotate the methods in
[ApplicationsMcpTools.java](quarkus-ai-mcp-applications/src/main/java/me/amiralles/applications/boundary/ApplicationsMcpTools.java)
with `@WithSpan` from `io.opentelemetry.instrumentation.annotations`.

### Reading a real trace

Both `agentgateway` and `applications-tools` appear in the Jaeger service dropdown. Here is an actual
`tools/call` through the full stack:

![Jaeger waterfall for a tools/call trace: duration 22.6ms across 2 services, depth 3, 4 spans. The root span
is agentgateway tools/call at 22.6ms, with two children — an agentgateway Guardrail span at 1.9ms, and an
agentgateway tools/call applications-tools span at 20.4ms, which in turn contains the applications-tools POST
/mcp span at 19.8ms](assets/jaeger.png)

The span tree is worth reading carefully:

```text
agentgateway  tools/call                   22.6ms   ← root: policy evaluation + proxying
├─ agentgateway  Guardrail                  1.9ms   ← the ExtMCP gRPC round trip
└─ agentgateway  tools/call applications-…  20.4ms  ← the proxied call
   └─ applications-tools  POST /mcp         19.8ms  ← Quarkus actually doing the work
```

The difference between the 22.6ms root span and the 19.8ms Quarkus span — roughly **2.8ms** — is your
**governance overhead**: JWT validation plus the guardrail round trip. The guardrail accounts for 1.9ms of
it, and because `failureMode: failClosed` puts it on the critical path, that cost is paid by *every single
tool call*. It is cheap here; it is also the first thing to measure if you add heavier inspection logic.

Note that the `Guardrail` span is emitted by **agentgateway**, not by the guardrail service — it is the
client side of the gRPC call. `quarkus-ai-mcp-guardrails` has no `quarkus-opentelemetry` dependency, so
there is no server-side span showing how those 1.9ms divide between network time and pattern matching. Adding
the extension plus the same OTel properties as
[quarkus-ai-mcp-applications](quarkus-ai-mcp-applications/src/main/resources/application.properties) would
close that blind spot.

### Production notes

`always_on` / `randomSampling: 1.0` is a lab setting — drop to 1–10% under real traffic. Jaeger all-in-one
keeps spans in memory and loses them on restart; use Tempo or similar with durable storage for anything you
intend to audit. Before exporting real data, define an attribute allowlist and redact PII — MCP tool arguments
carry exactly the personal data (`name`, `surname`, `personalId`) that must not land in a tracing backend.

---

## Running it

### Prerequisites

Java 25, Podman (or Docker), [agentgateway](https://agentgateway.dev/docs/), and optionally
[Goose](https://block.github.io/goose/). `jq` and `curl` for the verification steps.

### 1. Infrastructure

```shell script
cd podman && podman compose up -d
```

Starts PostgreSQL, Keycloak and Jaeger. On first run, follow the
[Keycloak setup](#one-time-keycloak-setup) above.

### 2. Build

```shell script
./mvnw clean install
```

### 3. Services

Start the guardrail **before** the gateway — `failClosed` means the gateway rejects tool calls without it.

```shell script
# terminal 1 — tool server
./mvnw -pl quarkus-ai-mcp-applications quarkus:dev

# terminal 2 — guardrail
./mvnw -pl quarkus-ai-mcp-guardrails quarkus:dev

# terminal 3 — gateway
agentgateway -f agentgateway/config.yaml
```

Dev UI for the tool server is at <http://localhost:8080/q/dev/>, gateway admin at <http://localhost:15000>,
Jaeger at <http://localhost:16686>.

### 4. Goose

Copy [goose/config.yaml](goose/config.yaml) into `~/.config/goose/config.yaml`
and substitute a real token for `<token>`, then:

```shell script
goose session
```

The extension points at `:3000`, not `:8080` — the gateway is the entry point.

---

## Verifying

### Authentication is enforced

Without a token, expect `401` and a `WWW-Authenticate` header:

```shell script
curl -i -X POST http://localhost:3000/mcp \
  -H 'content-type: application/json' \
  -H 'accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}'
```

Get a token and retry:

```shell script
export TOKEN=$(curl -s -X POST \
  http://localhost:8081/realms/mcp/protocol/openid-connect/token \
  -d grant_type=password -d client_id=agentgateway \
  -d username=mcpuser -d password=mcppassword -d scope=openid \
  | jq -r .access_token)

export MCP_SESSION_ID=$(curl -s -D - -o /dev/null -X POST http://localhost:3000/mcp \
  -H "authorization: Bearer ${TOKEN}" \
  -H 'content-type: application/json' \
  -H 'accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}' \
  | grep -i '^mcp-session-id:' | sed 's/.*: //' | tr -d '\r')
```

### The guardrail is in the path

Every tool description should come back prefixed with `[guardrail-verified]`:

```shell script
curl -s -X POST http://localhost:3000/mcp \
  -H "authorization: Bearer ${TOKEN}" \
  -H "mcp-session-id: ${MCP_SESSION_ID}" \
  -H 'content-type: application/json' \
  -H 'accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}' \
  | grep '^data: ' | sed 's/^data: //' | jq '.result.tools[].description'
```

Stop the guardrail module and repeat a `tools/call` — `failClosed` should now deny it.

### A blocked payload

A `tools/call` carrying a blocked pattern should be rejected by the guardrail, never reaching Quarkus (watch
the tool server log stay silent):

```shell script
curl -s -X POST http://localhost:3000/mcp \
  -H "authorization: Bearer ${TOKEN}" \
  -H "mcp-session-id: ${MCP_SESSION_ID}" \
  -H 'content-type: application/json' \
  -H 'accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_applications_by_country","arguments":{"countryName":"../etc/passwd"}}}'
```

### Validation at the boundary

A country name over 20 characters is rejected by Bean Validation in the tool server, after passing the gateway
and guardrail — demonstrating the innermost ring:

```shell script
curl -s -X POST http://localhost:3000/mcp \
  -H "authorization: Bearer ${TOKEN}" \
  -H "mcp-session-id: ${MCP_SESSION_ID}" \
  -H 'content-type: application/json' \
  -H 'accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"get_applications_by_country","arguments":{"countryName":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}}}'
```

### Traces

Open <http://localhost:16686>, pick `agentgateway` or `applications-tools`, and find the trace for the calls
above. One trace should span both services.

---

## Packaging

```shell script
./mvnw package
```

Produces `target/quarkus-app/` per module (not an über-jar; dependencies land in `target/quarkus-app/lib/`):

```shell script
java -jar quarkus-ai-mcp-applications/target/quarkus-app/quarkus-run.jar
java -jar quarkus-ai-mcp-guardrails/target/quarkus-app/quarkus-run.jar
```

Native executables — the `native` profile is declared on the parent and applies to both modules:

```shell script
./mvnw package -Dnative
# or, without a local GraalVM:
./mvnw package -Dnative -Dquarkus.native.container-build=true
```

Output: `<module>/target/<module>-1.0.0-SNAPSHOT-runner`.

---

## Related guides

- [Quarkus MCP Server](https://docs.quarkiverse.io/quarkus-mcp-server/dev/) — `@Tool`, HTTP transport, validator integration
- [Quarkus gRPC](https://quarkus.io/guides/grpc-getting-started) — the guardrail service transport
- [Quarkus OpenTelemetry](https://quarkus.io/guides/opentelemetry) — tracing
- [Hibernate ORM with Panache](https://quarkus.io/guides/hibernate-orm-panache) — persistence layer
- [agentgateway docs](https://agentgateway.dev/docs/) · [Keycloak integration](https://agentgateway.dev/docs/standalone/latest/integrations/auth/keycloak/)
- [Model Context Protocol](https://modelcontextprotocol.io/) — the specification

### Upstream workshop

This repository is an adaptation of Daniel Oh's
[Governed Agent Platform](https://danieloh30.github.io/governed-agent-platform/) workshop
([part 1](https://danieloh30.github.io/governed-agent-platform/tutorials/01-governed-mcp-tools/),
[part 2](https://danieloh30.github.io/governed-agent-platform/tutorials/02-agentgateway-security/),
[part 3](https://danieloh30.github.io/governed-agent-platform/tutorials/03-observability/)) to a real
persistence-backed service. The workshop's in-memory `customer-tools` are replaced by Panache entities
over PostgreSQL, and its hand-rolled JWT issuer is replaced by Keycloak, wired per agentgateway's
[Keycloak integration guide](https://agentgateway.dev/docs/standalone/latest/integrations/auth/keycloak/).
