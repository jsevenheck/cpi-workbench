# Security policy

## Reporting a vulnerability

Please report vulnerabilities privately through
[GitHub's private vulnerability reporting](https://github.com/jsevenheck/cpi-workbench/security/advisories/new),
not in a public issue. You should get an answer within a week.

## Scope

CPI Workbench runs locally and never connects to a tenant. Relevant reports include:

- XML runs (XSLT, XSD, XPath, flow steps, converters) that read or write files outside the open project folder,
- file operations that escape the project folder,
- web pages that can drive a local engine in browser mode,
- receiver requests that bypass import confirmation, cause automatic code execution, or bypass
  input/resource limits. The receiver is opt-in and loopback-only; any page may open a message link,
  so the confirmation is intentional rather than proof of the sender.

By design, Groovy scripts, Camel Simple method calls, XSLT `java:` calls and nested XSLT
`fn:transform()` are not sandboxed (see [What a run can access](docs/user-guide.md#what-a-run-can-access)).
Reports about ordinary execution of trusted code with the user's rights are not sandbox escapes;
unexpected execution without the required user action is relevant. Only run code you trust.

Trace links and project files may contain confidential data. The app does not authenticate CPI
Helper as a sender; use a local target and check the import dialog. MCP results can also contain
prompt injection, which clients must treat as data rather than instructions.
