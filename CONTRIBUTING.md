# Contributing

Thanks for your interest. Bug reports, fidelity findings from a real tenant and pull requests are
welcome.

## Ground rules

- **No SAP code or jars.** The `com.sap.*` classes are clean-room implementations of SAP's published
  signatures and documentation. Do not copy SAP sources, decompile SAP binaries or add SAP
  artifacts as dependencies.
- **Fidelity claims need evidence**: SAP's documentation, a published interface, Camel's or
  Saxon's behaviour, or output from a tenant (see [`probes/`](probes/README.md)). Label
  assumptions as assumptions, in code comments and in the docs.
- **No real credentials.** Files in `platform/`, test resources and examples hold test data only.
- Code, comments, commit messages and docs are in English. Match the style of the surrounding code.

## Development

[Building](docs/building.md) lists the requirements and commands. Before a pull request, run:

```sh
mvn -f engine/pom.xml verify          # engine tests
npm --prefix app run check            # Svelte/TypeScript checks
npm --prefix app test                 # UI unit tests
```

When behaviour changes, update the tests, [`docs/fidelity.md`](docs/fidelity.md) and
[`docs/status.md`](docs/status.md), and add a line under *Unreleased* in the
[changelog](CHANGELOG.md).

## Reporting a fidelity difference

If the simulator behaves differently from your tenant, open an issue with the input, the
mapping or script, what CPI returned and what the simulator returned. Remove anything
confidential first.
