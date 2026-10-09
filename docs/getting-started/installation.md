# Installation

Migrax is a command line tool. It needs **Java 17 or newer**; nothing else has to be installed,
and no administrator rights are needed.

## 1. Get Migrax

=== "From a release"

    Download `migrax-<version>.zip` from the
    [GitHub releases page](https://github.com/fsmutimeer/migrax/releases) and extract it.

=== "From source"

    ```bash
    git clone https://github.com/fsmutimeer/migrax.git
    cd migrax
    mvn clean install
    ```

    This also installs the Maven plugin and the libraries the integrations use into your local
    Maven repository.

## 2. Run the installer

From the extracted folder (or the source folder):

=== "Windows"

    ```powershell
    powershell -ExecutionPolicy Bypass -File install.ps1
    ```

    Installs to `%LOCALAPPDATA%\migrax` and adds its `bin` folder to your user `PATH`.

=== "macOS and Linux"

    ```bash
    sh install.sh
    ```

    Installs to `~/.local/share/migrax` and links `~/.local/bin/migrax`. Set
    `MIGRAX_INSTALL_DIR` and `MIGRAX_BIN_DIR` to choose other folders.

## 3. Check it

```console
$ migrax version
migrax 0.1.1
```

!!! tip "`migrax` is not recognized?"

    A terminal reads `PATH` once, when it starts. Windows that were already open before the
    installer ran don't see the new entry, so open a new terminal.

    Terminals inside an editor (VS Code, IntelliJ IDEA) copy the editor's `PATH` from when
    the editor started: close every editor window and open it again.

    To use the current PowerShell window right away:

    ```powershell
    $env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')
    ```

## Which Java does Migrax use?

Migrax uses `JAVA_HOME` when it is set, otherwise the `java` on your `PATH`. To run it with a
different Java for one terminal session, set `JAVA_HOME` there:

=== "Windows (PowerShell)"

    ```powershell
    $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot"
    ```

=== "macOS and Linux"

    ```bash
    export JAVA_HOME=/path/to/jdk-25
    ```

Extra JVM options go in `MIGRAX_JAVA_OPTS`.

## Updating and uninstalling

Run the installer of the new version again to update. To uninstall, delete the install folder
and remove its `bin` folder from `PATH`.

Next: [Quick start](quickstart.md).
