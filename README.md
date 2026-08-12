# union-control

Spring control plane for the Union PydanticAI application.

Configuration:

- `PY_APP_BASE_URL`
- `BEHAVIOR_RISK_TOKEN` for the fixed behavior-risk scenario
- `SCHEDULED_TASK_TOKEN` shared only by control and py-app scheduled routes
- `SCHEDULED_TASK_ENABLED` (default `false`; enable after both services share the token)
- `SCHEDULED_TASK_SCAN_INTERVAL_MS` (default `5000`)
- `SCHEDULED_TASK_WORKER_THREADS` (default `2`)
- `SCHEDULED_TASK_WORKER_QUEUE` (default `32`)
- `SCHEDULED_TASK_MAX_RUN_SECONDS` (default `930`)
- `SCHEDULED_TASK_MIN_INTERVAL_SECONDS` (default `60`)
- `SCHEDULED_TASK_CONNECT_TIMEOUT_MS` (default `5000`)
- `SCHEDULED_TASK_READ_TIMEOUT_MS` (default `930000`; includes 30 seconds of margin beyond py-app's 900-second runtime deadline)
- `AGENT_MAX_RUN_SECONDS` (default `900`)
- `AGENT_CANCEL_GRACE_SECONDS` (default `30`)
- `AGENT_CLEANUP_INTERVAL_MS` (default `30000`)
- `MYSQL_URL`, `MYSQL_USER`, `MYSQL_PASSWORD` (`MYSQL_URL` must use a UTC connection timezone)
- `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `REDIS_TIMEOUT_MS`
- `SENSITIVE_REVEAL_TTL_SECONDS` (default `300`)

Scheduled-task browser routes require the caller's same-origin
`CASSESSIONID`; the local `LocalAuth` mock validates `session-1` and must be
replaced by the production authentication-service integration. Normal
control-to-py calls forward only the authenticated CAS session. Fixed scenario
routes use the reusable Authorization-token path instead. If
`PY_APP_BASE_URL` is not loopback, use HTTPS with mTLS or an equivalent
authenticated service mesh so bearer credentials never cross a plaintext
network boundary.

The database is initialized from `src/main/resources/schema.sql`. For the
startup-only incompatible execution-model cutover, run
`deploy/sql/reset_agent_schema.sql` once before starting the new version; it
deletes the unreleased conversation and memory data so the five target tables
can be created cleanly. The current
schema is a clean AG-UI/Memory schema; there is no legacy migration or
compatibility layer.

Natural-language scheduled tasks use the two `agent_scheduled_task*` tables.
The scheduler stores only the successful py-app result's `content` and
`agentName` on the run; opening an unread run atomically creates the normal
AG-UI conversation and messages.
Existing databases must apply
`deploy/sql/20260810_add_agent_scheduled_tasks.sql` before enabling the
scheduler.
Startup fails closed when scheduling is enabled without `SCHEDULED_TASK_TOKEN`.
Each user may keep at most 100 `ACTIVE` or `PAUSED` tasks.

The isolated sensitive-data demo mirrors the production database boundary.
`POST /api/sensitive/demo` and `GET /api/sensitive/demo` use a MyBatis field
interceptor that encrypts before a database write and decrypts after a read.
The existing decrypt path exposes one hook; its reveal implementation replaces
the plaintext with a masked value plus a short-lived, owner-bound token. The
independent `POST /api/sensitive/reveal` API resolves that token and decrypts
the ciphertext without re-entering the masking hook. Send the local mock cookie
`CASSESSIONID=session-1`. The local crypto dependency remains Base64URL-only;
production supplies its existing AES implementation at the same boundary.

Run the Java 8 test suite with:

```bash
mvn clean test
```
