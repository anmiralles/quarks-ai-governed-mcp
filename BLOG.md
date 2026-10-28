Your MCP server is an API that takes its instructions from a language model. Secure it like one.

MCP is how AI agents reach real systems. The moment a tool writes to a database, three questions matter: who is calling, what may they call, and what is inside the call? Most MCP demos answer none of them.

I built a reference implementation in Quarkus to work through all three.

𝗧𝗵𝗲 𝗮𝗿𝗰𝗵𝗶𝘁𝗲𝗰𝘁𝘂𝗿𝗲

An MCP tool server over PostgreSQL. In front of it, agentgateway — a proxy that parses JSON-RPC, so it reasons about which tool is being invoked, not just "POST /mcp". Beside it, a gRPC guardrail service. Behind it, Keycloak and Jaeger. Clients never touch the tool server.

𝗔𝘂𝘁𝗵𝗲𝗻𝘁𝗶𝗰𝗮𝘁𝗶𝗼𝗻

Keycloak issues JWTs. The gateway validates signature, issuer and audience before a request goes anywhere. The Quarkus service contains zero auth code.

𝗔𝘂𝘁𝗵𝗼𝗿𝗶𝘀𝗮𝘁𝗶𝗼𝗻

Because the proxy speaks MCP, policy lands at tool granularity, not URL granularity: CEL rules over tool name and JWT claims. A read-only role doesn't just get denied — unauthorised tools vanish from tools/list, so the agent never learns they exist. This is the layer I'm wiring next.

𝗚𝘂𝗮𝗿𝗱𝗿𝗮𝗶𝗹𝘀

This is the part unique to AI. Tool arguments are composed by a model from text that may be attacker-influenced; a successful prompt injection turns your agent into a confused deputy holding a perfectly valid token. Authentication cannot help — the caller really is who they claim to be.

Worse, tool descriptions returned by tools/list are fed straight into the model's context. A hostile description is an instruction to your agent. That's tool poisoning.

So a gRPC service inspects both directions: scanning arguments for path traversal and prototype pollution, sanitising headers against CRLF injection, screening descriptions on the way back. It runs failClosed — if the guardrail is down, calls are denied rather than waved through. A control that disables itself on failure isn't a control.

𝗢𝗯𝘀𝗲𝗿𝘃𝗮𝗯𝗶𝗹𝗶𝘁𝘆

One agent prompt fans out into several MCP round trips across three processes. In access logs those are unrelated records. With W3C trace context propagated from gateway to Quarkus, they become a single waterfall in Jaeger.

From a real trace: 22.6ms end to end, 19.8ms of it the actual work. Governance costs ~2.8ms, of which the guardrail round trip is 1.9ms — paid on every single call, because failClosed puts it on the critical path. Worth measuring before you add heavier inspection.

That trace is also the audit trail: one ID tying a prompt to every policy decision and every tool call.

𝗧𝗵𝗲 𝗽𝗼𝗶𝗻𝘁

Security and observability here are configuration and a sidecar, not business logic. Add a tool and it inherits the policy automatically. That separation is what makes governance reviewable, and changeable without redeploying the service doing the work.

Repo: [https://github.com/anmiralles/quarks-ai-governed-mcp]

Adapted from Daniel Oh's Governed Agent Platform workshop.

#MCP #AIAgents #Quarkus #Java #Security #Observability #OpenTelemetry
