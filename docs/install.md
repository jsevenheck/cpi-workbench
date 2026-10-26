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

### If "Run anyway" is missing

On a managed company laptop, **Run anyway** may be unavailable. If your organisation permits
installing CPI Workbench, you can try removing the internet-download marker from this one installer
with PowerShell. This is not a way around company security policies.

1. Verify that the installer came from the release page above. Download `SHA256SUMS.txt` from the
   same release and compare its entry with the output below. Replace `<version>` with the version
   you downloaded, and adjust the path if your Downloads folder is elsewhere:

   ```powershell
   Get-FileHash -Algorithm SHA256 -LiteralPath "$env:USERPROFILE\Downloads\CPI-Workbench_<version>_x64-setup.exe"
   ```

   If the checksum differs, delete the installer and do not run it. A matching checksum checks
   consistency with the release; it does not replace checking the source or IT approval.
2. Only after checking the source and checksum, unblock that file:

   ```powershell
   Unblock-File -LiteralPath "$env:USERPROFILE\Downloads\CPI-Workbench_<version>_x64-setup.exe"
   ```

3. Start the installer:

   ```powershell
   Start-Process -FilePath "$env:USERPROFILE\Downloads\CPI-Workbench_<version>_x64-setup.exe"
   ```

Start with a normal PowerShell window. The per-user `.exe` normally needs no administrator rights.
If access is denied and your IT department authorises elevation, open PowerShell with **Run as
administrator** and repeat the commands using the actual path to your download. If elevation uses
a different administrator account, `$env:USERPROFILE` points to that account, not yours.

The same `Unblock-File -LiteralPath "C:\path\to\installer.msi"` command can remove the download
marker from an `.msi`. Then install it with `msiexec.exe /i "C:\path\to\installer.msi"`; the MSI
installation requires administrator approval.

[Microsoft documents `Unblock-File`](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.utility/unblock-file?view=powershell-5.1)
as removing the `Zone.Identifier` download marker, not verifying or signing the file. It does not
guarantee that SmartScreen or other security controls will allow installation. If the installer
is still blocked, stop and ask IT; do not disable security tools or change execution policies.
The command-line jars are an alternative only if your organisation allows them too.

### If the Groovy engines do not start

Java cannot start from a folder whose path has characters outside the system's code page, for
example Polish, Czech or Chinese letters in the user name on a German or English Windows
([JDK-8205991](https://bugs.openjdk.org/browse/JDK-8205991)). The app then starts the Groovy engines
through a link in `C:\ProgramData\CPI Workbench <user SID>`, which only you and administrators can
change. Uninstalling leaves that folder behind; you can delete it. If the engines still do not
start, reinstall into a folder with plain ASCII characters, such as `C:\CPI Workbench`.

## macOS

1. Open the `.dmg` and drag **CPI Workbench** to *Applications*.
2. On the first start, right-click the app and choose **Open**, then **Open** again. On recent
   macOS versions, open *System Settings → Privacy & Security* and choose **Open Anyway** instead.
3. If macOS says the app "is damaged", do not assume the warning is harmless: the download may be
   corrupt or modified. Quarantine can also prevent the unsigned app from opening. Before you lift
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

The quarantine flag is macOS's closest equivalent to Windows's download marker; `Unblock-File`
is not the macOS procedure. Prefer **Open Anyway** as described in
[Apple's guidance](https://support.apple.com/en-us/102445). On a managed Mac, these settings may be
unavailable: ask IT rather than using the terminal command to work around a restriction. Do not
disable Gatekeeper globally or override a warning that the app contains malware.

## Linux

```sh
sudo apt install ./CPI-Workbench_<version>_amd64.deb      # Ubuntu, Debian
sudo dnf install ./CPI-Workbench-<version>-1.x86_64.rpm   # Fedora, RHEL
```

The app needs WebKitGTK 4.1, which the package manager installs with it.

For these `.deb` and `.rpm` packages there is no equivalent `Unblock-File` step: install them with
the package manager, which needs administrator (`sudo`) permission. Do not use `chmod +x` on these
packages or run them as executables. Verify the download against `SHA256SUMS.txt` first, for example
with `sha256sum ./CPI-Workbench_<version>_amd64.deb`, and compare the result with its release entry.
If your company restricts package installation, ask IT; do not disable package-signature checks or
other security controls. See also [Ubuntu's package-management guide](https://documentation.ubuntu.com/server/how-to/software/package-management/).

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
