# Security policy

## Reporting a vulnerability

Please report vulnerabilities privately through
[GitHub's private vulnerability reporting](https://github.com/jsevenheck/cpi-workbench/security/advisories/new),
not in a public issue. You should get an answer within a week.

## Scope

CPI Workbench runs locally and never connects to a tenant. Relevant reports include:

- XSLT, XSD or XPath runs that read or write files outside the open project folder,
- file operations that escape the project folder,
- web pages that can drive a local engine in browser mode.

Out of scope by design: Groovy scripts run with the user's rights and are not sandboxed, and XSLT
`java:` calls run JDK code (see the [user guide](docs/user-guide.md#what-a-run-can-access)). Only
run scripts and mappings you trust.
