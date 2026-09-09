# Sensitive reveal production boundary

This repository is a local control-plane mock. Its production changeset is deliberately narrower
than the files required to compile the local simulation.

## Existing production files modified

- `com/union/control/mapper/interceptor/AESInterceptor.java`
- `com/union/control/utils/security/SymmetricalSecurityUtils.java`

The interceptor keeps the restored 70 statement IDs and dynamic `interceptorItems` behavior, but
routes general encryption, general decryption, and the AddressBook migration separately. All
sensitive queries use the global reveal output mode. AddressBook rows whose returned `role` key is
listed in `sensitive.address-book.plaintext-roles` are decrypted directly; all other rows use the
shared masking and Redis-token flow. The utility keeps
the existing `@Resource(name = "sensitiveProxy") SymmetricalSecurityService` boundary,
`AES256` calls, and `Result<SecurityResult>` success/error contract.

## New production files

- reveal processor, token store, service, controller, and exception mapping

The production data path is intentionally limited to five Java files: the interceptor,
AddressBook branch, reveal processor, Redis token store, and reveal service. Routing, statement
copying, field transformation, role parsing, and property injection stay with those owners instead
of introducing one-implementation policy or wiring classes.

The token store delegates to the existing production `RedisCacheService` contract (`setex` and
`get`). The service does not expose a batch API, so the adapter cannot claim pipeline semantics;
it publishes clickable tokens only when every `setex` reports success.
- focused compatibility and reveal tests

No existing Mapper, Mapper XML, business service, authentication filter, annotation, constant,
external proxy, algorithm, database format, or dependency declaration is changed. Reveal queries
disable second-level caching on the interceptor's copied `MappedStatement` and clear the current
Executor cache in `finally`; the existing Mapper XML stays unchanged to preserve the requested
production file boundary.

## Local-only files — do not deploy

- `com/union/control/local/sensitive/LocalMockSensitiveProxy.java`
- `com/union/control/local/sensitive/LocalSensitiveProxyConfiguration.java`
- `com/union/control/local/sensitive/LocalSensitiveHostConfiguration.java`
- `com/union/control/local/sensitive/LocalRedisCacheService.java`
- local compatibility copies under `com/epcc/commons/securityproxy`, `com/epcc/dubbo/result`,
  and the unchanged production annotation/constant/exception contracts copied into this mock
- `application.properties`

Those sources live under `ark-control/src/local-mock/java` and are added only by the
`local-sensitive-compat` Maven profile. The local profile also owns the mock app's interceptor
registration. Production keeps its existing interceptor registration and does not compile this
source set.

The mock proxy is intentionally non-cryptographic and is enabled by the local
`default` or explicit `local-sensitive-mock` Spring profile. It is excluded from
production, which continues to supply its existing `sensitiveProxy` bean.
The production reveal controller integration excludes demo methods and their
constructor/service dependencies; the combined local demo controller is not
copied wholesale.
