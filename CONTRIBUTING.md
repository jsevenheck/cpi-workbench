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
npm --prefix app run build            # catches Svelte compilation errors too
```

When behaviour changes, update the tests, [`docs/fidelity.md`](docs/fidelity.md) and
[`docs/status.md`](docs/status.md), and add a line under *Unreleased* in the
[changelog](CHANGELOG.md). If a probe's output changes, update its simulator baseline too.
Run shell/MCP/receiver tests for Rust changes and browser e2e for UI workflows. `flowcalls.ts`,
`whereused.ts`, `functions.ts` and `iflowmodel.ts` have Rust twins in the MCP server: change both and the
fixture in `testdata/*-parity` that both replay ([AGENTS.md](AGENTS.md#layout));
[Building](docs/building.md) explains engine staging and the no-GraalVM override.

Keep documentation about the current checkout distinct from release history and pending roadmap
items. Preserve fidelity evidence and source IDs rather than deleting unanswered questions.

## Reporting a fidelity difference

If the simulator behaves differently from your tenant, open an issue with the input, the
mapping or script, what CPI returned and what the simulator returned. Remove anything
confidential first.
