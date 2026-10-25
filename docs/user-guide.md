# User guide

## Desktop app

Download the installer for your platform from the CI artifacts (see [Building](building.md)),
install it and open a project folder. Any folder works; **Init** adds `mappings/`, `schemas/`,
`scripts/`, `tests/` and example files in `platform/`.

* Open a `.xsl` or `.groovy` file in the tree and enter the input body, headers and properties on the left.
* The output updates live, or press **Ctrl+Enter**. Compile and runtime errors are marked in the editor.
* **Parameters** (XSLT) shows how each `xsl:param` was bound: header, property or default.
* **MPL**, **Console** and **API calls** (Groovy) show message processing log entries, `println`
  output and the calls to SAP APIs with their arguments.
* **Compare** also runs the input on the other runtime of the same kind (Saxon-HE 9.9 ↔ 12,
  Groovy Script 1.x ↔ 2.x) and lists what differs: status, headers, properties and a body diff.
  This helps before you move a script from 1.x to 2.x.
* **Save as test** stores the input, the mapping or script reference and the current output as a
  regression test (see [Test cases](test-cases.md)). Opening a test case loads it into the editor.
  Inputs only a case file can hold (attachments, SOAP headers, platform overrides) are kept while
  the case is open and are shown below the input.
* **Tests** runs all test cases of the project, each on the engine its profile names.
* **XSD** validates a document against a schema; **XPath** evaluates an expression (1.0 or 3.1).
  XSD test cases open in the XSD view too. Saving one from there stores the verdict (valid or
  not); add `errorContains` to `case.json` by hand if a message should be checked.
* **Import iFlow** unpacks an iFlow exported from Integration Suite (zip) into `iflows/` and lists
  its Groovy Script, XSLT Mapping and XML Validator steps. Clicking a step opens its file with the
  step's function name and script version. Steps that choose their mapping from a header at runtime
  are marked. The `.iflw` format is not publicly documented, so this is read generically (best effort).
* **Lint** shows hints about what breaks when a script moves from 1.x to 2.x:
  * `XmlSlurper` or `XmlParser` used without a `groovy.xml` import,
  * Script API v1 imports or v1-only methods in a 2.x step,
  * wildcard imports.

The installers are not code-signed yet. On macOS, right-click the app and choose *Open* the first
time. On Windows, SmartScreen asks once ("More info" → "Run anyway"). The Windows installer
installs per user and needs no admin rights.

The Groovy runtime starts only when the Groovy tab is used and stops after 10 idle minutes.

## Command line (CI-friendly)

```sh
java -jar cpiwb-groovy-v1.jar run --script scripts/enrich.groovy --in input.xml -H SenderSystem=ERP
java -jar cpiwb-groovy-v2.jar test examples/demo-project/tests
cpiwb-xml xslt --xsl mappings/order.xsl --in input.xml -H SenderSystem=ERP -P Count=3:Integer
cpiwb-xml validate --xsd schemas/order.xsd --in output.xml
cpiwb-xml xpath --expr "//Item/@sku" --in input.xml --version 3.1
cpiwb-xml test examples/demo-project/tests        # exit code 1 if a case fails
```

`-H` sets a header and `-P` an exchange property, as `name=value[:Type]`. The types are listed in
[Test cases](test-cases.md). Each engine runs only the cases of its own kind and skips the others.

## Browser mode (no desktop shell needed)

```sh
cpiwb-xml-he99 serve --http 7777 --ui app/dist    # then open http://127.0.0.1:7777
```

Web mode listens on localhost only. The engine that serves the page also handles the project's
files. To add more engines, pass them in a URL parameter:

```sh
java -jar cpiwb-groovy-v1.jar serve --http 7778 &
java -jar cpiwb-groovy-v2.jar serve --http 7779 &
# open http://127.0.0.1:7777/?engines=groovy-1.x@http://127.0.0.1:7778,groovy-2.x@http://127.0.0.1:7779
```
