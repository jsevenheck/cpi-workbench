# Installing CPI Workbench

Download the file for your system from the
[latest release](https://github.com/jsevenheck/cpi-workbench/releases/latest). Each installer
brings everything it needs: the XSLT engines and a Java runtime for the Groovy engines. You do not
need Java, Groovy or Saxon on your machine.

| System | File |
|---|---|
| Windows 10/11 (x64) | `CPI-Workbench_<version>_x64-setup.exe`, or the `.msi` |
| macOS 11 or later, Apple Silicon | `CPI-Workbench_<version>_aarch64.dmg` |
| Linux x64 | `.deb` (Ubuntu 22.04+, Debian 12+) or `.rpm` (Fedora, RHEL) |
| Command line, any OS with Java 17+ | `cpiwb-xml-he99-<version>.jar` and the other `cpiwb-*.jar` files |

There is no build for Intel Macs yet. `SHA256SUMS.txt` in the release lists the checksums of all
files.

The installers are **not code-signed** yet, so Windows and macOS warn on the first start.

## Windows

1. Run `CPI-Workbench_<version>_x64-setup.exe`. It installs for your user only and needs no admin
   rights. The `.msi` installs for all users of the machine and needs admin rights; it is meant for
   administrators and software distribution tools.
2. If SmartScreen says "Windows protected your PC", choose **More info** → **Run anyway**.

Java cannot start from a folder whose path has characters outside the system's code page, for
example Polish, Czech or Chinese letters in the user name on a German or English Windows
([JDK-8205991](https://bugs.openjdk.org/browse/JDK-8205991)). The app then starts the Groovy engines
through a link in `C:\ProgramData\CPI Workbench <user SID>`, which only you and administrators can
change. Uninstalling leaves that folder behind; you can delete it. If the engines still do not
start, reinstall into a folder with plain ASCII characters, such as `C:\CPI Workbench`.

Managed company devices may block unsigned installers altogether. Ask your IT department, or use the
command-line jars.

## macOS

1. Open the `.dmg` and drag **CPI Workbench** to *Applications*.
2. On the first start, right-click the app and choose **Open**, then **Open** again. On recent
   macOS versions, open *System Settings → Privacy & Security* and choose **Open Anyway** instead.
3. If macOS says the app "is damaged", this usually means Gatekeeper refuses the unsigned app in
   quarantine, but it can also mean the download is corrupt or not the original. Before you lift
   the quarantine, make sure the `.dmg` comes from the release page above and that its checksum
   matches the line in `SHA256SUMS.txt`:

   ```sh
   shasum -a 256 ~/Downloads/CPI-Workbench_<version>_aarch64.dmg
   ```

   Only if both are right, remove the quarantine flag once:

   ```sh
   xattr -dr com.apple.quarantine "/Applications/CPI Workbench.app"
   ```

   If the checksum differs, delete the download and do not open it.

## Linux

```sh
sudo apt install ./CPI-Workbench_<version>_amd64.deb      # Ubuntu, Debian
sudo dnf install ./CPI-Workbench-<version>-1.x86_64.rpm   # Fedora, RHEL
```

The app needs WebKitGTK 4.1, which the package manager installs with it.

## Command line

The engine jars run test cases in a terminal or a CI pipeline. Each needs Java 17 or later:

```sh
java -jar cpiwb-xml-he99-<version>.jar test path/to/project/tests     # XSLT and XSD cases
java -jar cpiwb-groovy-v1-<version>.jar test path/to/project/tests    # Groovy Script 1.x cases
java -jar cpiwb-groovy-v2-<version>.jar test path/to/project/tests    # Groovy Script 2.x cases
```

See the [user guide](user-guide.md#command-line-ci-friendly) and [test cases](test-cases.md).

## First steps

1. Start the app and open a folder as a project. A new folder gets the standard layout with **Init**.
2. To try things out, download the repository and open
   [`examples/demo-project`](../examples/demo-project/README.md).
3. Open a mapping or script, enter the input message and press **Ctrl+Enter**.

The [user guide](user-guide.md) explains the rest.

## Licences

The installers contain a `licenses` folder with the licence texts of every bundled component (on
macOS inside the app bundle, under `Contents/Resources/licenses`). The engine jars carry the same
texts under `META-INF/third-party/`. The list is in [`licenses/README.md`](../licenses/README.md).
