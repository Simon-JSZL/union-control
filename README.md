# union-control

Spring control plane for the Union PydanticAI application.

Configuration:

- `PY_APP_BASE_URL`
- `BEHAVIOR_RISK_TOKEN` for the fixed behavior-risk scenario
- `AGENT_MAX_RUN_SECONDS` (default `900`)
- `AGENT_CANCEL_GRACE_SECONDS` (default `30`)
- `AGENT_CLEANUP_INTERVAL_MS` (default `30000`)
- `MYSQL_URL`, `MYSQL_USER`, `MYSQL_PASSWORD`

Browser requests do not provide identity credentials. Control obtains the
authenticated CAS session from the authentication service; the local
`LocalAuth` mock generates it. Normal control-to-py and py-to-control calls
forward only that `CASSESSIONID`. Fixed scenario routes use the reusable
Authorization-token path instead.

The database is initialized from `src/main/resources/schema.sql`. For the
startup-only incompatible execution-model cutover, run
`deploy/sql/reset_agent_schema.sql` once before starting the new version; it
deletes the unreleased conversation and memory data so the five target tables
can be created cleanly. The current
schema is a clean AG-UI/Memory schema; there is no legacy migration or
compatibility layer.

Run the Java 8 test suite with:

```bash
mvn clean test
```
