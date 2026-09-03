# Direct Agent SSE proxy

`POST /llm/chatMessage` is owned only by `ark-web`, so its stream does not cross
Dubbo. `LlmController` posts the authenticated payload directly to py-app
`/agent/v1/runs`, copies the upstream status and SSE headers, and writes and
flushes every byte chunk to the browser response.

The remaining Agent operations use `AgentProxyService` through Dubbo:

- `sync`
- `scheduled`
- `cancel`

`HttpServletResponse` and streaming types stay out of `ark-control-facade`.
Neither HTTP nor Dubbo filters may log cookies, authorization values, model
payloads, responses, or SSE chunks.
