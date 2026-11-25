# LiteLLM Connector

Low-code, **read-only** [SCIMREST](https://gitlab.evolveum.com/evolveum/scimrest-connector)-based
connector for a [LiteLLM](https://docs.litellm.ai/) Proxy.

The connector is driven entirely by a manifest (`connector.manifest.yaml`) plus YAML/Groovy
schema and operation scripts — there is no per-connector Java code. The bundle's entry point is
`com.evolveum.polygon.scimrest.groovy.impl.ManifestBasedConnector` provided by
`connector-scimrest-common`.

The LiteLLM connector is the reference implementation of this family of connectors: nine object
classes (User, VirtualKey, Model, Endpoint, Agent, MCP, Guardrail, Team, Organization)
declared in YAML (native / ConnID / search scripts) plus a Groovy
`relationships.schema.groovy` that declares the reference attributes as
relationships with subject/object roles (midPoint requires a role on every
reference), driven by the built-in bearer authentication and the
`/health/liveliness` test endpoint.

## Building

Prerequisites: Java 21, Maven 3.9+, network access to `nexus.evolveum.com`
(the SCIMREST framework artifacts are not on Maven Central).

```sh
mvn clean package
```

This builds the connector bundle: `target/connector-litellm-<version>.jar` (bundle with `lib/`
dependencies and `META-INF/conndev-doc/` documentation).

The SCIMREST framework version is a property, defaulting to the current development snapshot:

```sh
# default: -Dscimrest.version=0.2.1-SNAPSHOT
# pin to a release for reproducible builds:
mvn clean package -Dscimrest.version=0.2.0
```

## Testing

Smoke tests run against WireMock:

```sh
mvn test
```

Live tests against a real LiteLLM Proxy (API key via `LITELLM_API_KEY`, base URL via
`LITELLM_BASE_URL`, default `http://192.168.1.10:14000`) run in the `live-tests` profile:

```sh
mvn -P live-tests verify
```

## Connector layout

```
├── pom.xml
└── src/main/
    ├── assembly/
    │   └── connector.xml        # bundle assembly descriptor
    └── resources/
        ├── connector.manifest.yaml   # manifest: application info + schema/operation scripts
        ├── Messages.properties       # display names and help texts for configuration
        └── configurationOverride.properties  # defaults/overrides for the connector configuration
```

## Provenance

Extracted from the [agent-connector-prototype](https://gitlab.evolveum.com/evolveum/agent-connector-prototype)
repository (branch `migrate/litellm`); the commit history of this connector is preserved.
