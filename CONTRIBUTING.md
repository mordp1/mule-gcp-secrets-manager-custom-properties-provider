# Contributing

Issues and pull requests are welcome.

## Development

- JDK 17 and Maven 3.9+.
- `mvn clean verify` builds the plugin and runs the unit tests. The tests use an in-process fake Secret
  Manager endpoint and need no GCP access.
- To try a change in a real runtime, run `mvn install`, then build `examples/demo-app` and deploy it to a
  Mule 4.6+ runtime (Studio, standalone, or RTF).

## Guidelines

- Keep the dependency footprint small. The plugin runs inside customers' Mule apps, so avoid gRPC, Guava,
  Protobuf or other libraries that commonly conflict with connectors.
- Never log secret values, and never include them in exception messages.
- Add a unit test for every new placeholder form or configuration parameter. Document it in `README.md`
  and `CHANGELOG.md`.
- All code, comments and documentation are in English.
