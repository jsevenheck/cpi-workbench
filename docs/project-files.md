# Project files and platform data

## Project layout

```
my-project/
  cpi-workbench.json      # marks the folder as a project (name)
  mappings/  scripts/  schemas/
  inputs/                 # saved input bodies
  lib/                    # extra .jar files on the script class path
  platform/               # mocked platform data (see below)
  iflows/                 # imported iFlow exports
```

**Init** in the app creates `mappings/`, `schemas/`, `scripts/` and `platform/` with example files, and
`cpi-workbench.json`. **Import iFlow…** without a project does the same in a new folder it creates (never an
existing one) and imports the export into it. Any folder can be opened as a project, including a symlink or Windows junction
that points to the intended root. Init checks its destinations before writing and never replaces existing
files. Links below the selected root must resolve inside it. The file tree omits paths it cannot follow;
iFlow discovery reports blocked branches or files without reading them. A link to another folder inside the
project (for example `iflows/Alias` to `iflows/Local`) shows both names in the file tree, but Steps, Diagram
and Where used read each iFlow once, under the name that comes first in path order. If the project folder is
deleted or renamed while open, calls that need it fail with *Project folder is unavailable*; reopen the project.

Imports stage and check archives before writing to the project. An import can unpack up to 200 MiB
across its artifacts and count up to 10,000 ZIP entries, including those of an outer package. A bad
archive or a limit error during staging leaves the project unchanged. Packages are not filesystem
transactions: if publication fails, review the named folders before retrying. A create-new import that
fails while writing tries to remove only the entries it created, not files added by another writer. If
cleanup cannot remove the folder, the error tells you to review its contents. An archive with two entries for
one file (the same name twice, or names that differ only in letter case on a file system that does not
tell them apart) is refused. The Value Mapping table is replaced atomically, after the package's folders
have been published, and keeps its file permissions.

**Overwrite imports do not delete files.** They replace the files the export contains and keep every other
file in the artifact's folder, such as a script or `.iflw` the tenant no longer has, or files of your own.
Kept files still appear in Steps, Diagram and Where used. The import result lists them (`kept`, as
project-relative paths); delete the ones you no longer need, or import into a new folder.

## Header and property types

A header or property is a string unless it names one of these types: `String`, `Integer`, `Long`,
`Boolean`, `Double`, `Float`, `BigDecimal`, `Date` (ISO-8601), `byte[]` (Base64), `null`. The app's
Headers and Properties tables offer them, the command line takes them as `-H name=value:Type`, and the
MCP server as `{"value": "…", "type": "Integer"}`. A property of type `null` is left out with a warning:
Camel keeps no property with a null value (`Exchange.setProperty` removes it). A header can be `null`.

## Script inputs

* **Libraries**: jars in the project's `lib/` folder and in an imported iFlow's
  `src/main/resources/lib/` are on the script's class path. The engines also bundle the libraries
  identified by tenant fingerprints (commons-lang3 on both; Jackson and commons-codec on 1.x;
  yaml/toml support on 2.x). Exact versions and exclusions: [Fidelity](fidelity.md).
* **Java calls from XSLT**: `xmlns:x="java:java.lang.Math"` style calls (`x:max(1, 2)`,
  `x:new(…)`, instance methods with the object as first argument) work for JDK classes (the native
  engines: a fixed list of common ones). Classes from `lib/` jars are not available to XSLT.
  Hyphenated names (`random-UUID`) work too.
* **Message Mapping functions**: a custom function of a Message Mapping script, such as
  `def String f(String a, MappingContext c)` or `def void f(String[] a, Output output, MappingContext c)`,
  runs with arguments instead of a body; `Output` and `MappingContext` come from the simulator. The
  signature decides how the function is called: with `String` arguments once (*Single values*), with
  `String[]` arguments once per context (*All values of a context*), which needs the same number of
  contexts in every `String[]` argument. `MappingContext` reads and sets the input's headers and
  properties. How to enter the arguments: [User guide](user-guide.md#groovy-completions-and-message-mapping-functions).

## Mocked platform data (`platform/`)

| File | Used by | Format |
|---|---|---|
| `credentials.json` | `SecureStoreService` | `{ "ALIAS": { "username": "…", "password": "…", "properties": {…} } }`. For OAuth2 credentials (`getAccesTokenForOauth2ClientCredential`, `…AuthorizationCodeCredential`) add `"accessToken": "…"`, optionally `"user"` and `"tokenResponse": {…}`. No token server is called. |
| `value-mappings.json` | `ValueMappingApi.getMappedValue` | `[ { "sourceAgency", "sourceIdentifier", "sourceValue", "targetAgency", "targetIdentifier", "targetValue" } ]` |
| `partner-directory.json` | `PartnerDirectoryService` | `{ "partners": { "PID": { "parameters": { "NAME": "string" or { "contentType": "…", "file": "x.bin" } or { "base64": "…" } }, "alternativePartners": [ { "agency", "scheme", "id" } ], "authorizedUsers": [ "user" ] } } }`. A binary parameter with content type `crt` is returned by `getCertificateParameter` as an X.509 certificate. |
| `keystore.json` | `KeystoreService` | `{ "file": "keystore.p12", "password": "…", "type": "PKCS12", "keyPasswords": { "alias": "…" } }`. The keystore file lies next to it; `type` is optional. Key and trust managers use the same file. |
| `number-ranges.json` | `NumberRangeConfigurationService` | `{ "NAME": { "next": 1, "min": 1, "max": 999999, "fieldLength": 6, "rotate": false } }`. Values count up within one run only and are not written back. The same correlation ID returns the same value. Zero padding to `fieldLength` is an assumption. |

In the app, the Groovy tab's **Platform** tab edits these files as tables (the keystore as a file); see the
[user guide](user-guide.md).

**Use test-only keys and passwords here, never real tenant secrets.**

Behaviour for missing entries follows SAP's published Javadoc and a tenant's probe results (the
messages too, [plan](plan.md) F52):

* The Partner Directory and the Keystore return `null` for a missing entry or an unknown alias
  (also `getAuthorizedUsers` of an unknown partner). Without `keystore.json` the keystore is empty.
* The Secure Store throws `SecureStoreException` for an unknown alias.
* `ITApiFactory` throws `InvalidContextException` for a service the simulator does not offer.
