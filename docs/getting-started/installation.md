# Installation

Migrax is a command line tool. It needs **Java 17 or newer**; nothing else has to be installed,
and no administrator rights are needed.

## With a package manager

The quickest way, if you use one of these:

=== "Homebrew (macOS and Linux)"

    ```bash
    brew install fsmutimeer/migrax/migrax
    ```

    Homebrew installs Java with it. When `JAVA_HOME` is set, Migrax uses that Java instead.

=== "Scoop (Windows)"

    ```powershell
    scoop bucket add migrax https://github.com/fsmutimeer/scoop-migrax
    scoop install migrax
    ```

    Migrax needs Java 17 or newer. If you don't have it, install one with Scoop too:
    `scoop bucket add java`, then `scoop install java/temurin21-jdk`.

Then check it with `migrax version` and continue with the [Quick start](quickstart.md). The
package managers get every stable release on the day it is published.

Without a package manager, install Migrax from its ZIP in three steps:

## 1. Get Migrax

=== "From a release"

    1. Open the [Download](../download.md) page and click **Download migrax-&lt;version&gt;.zip**.
    2. Extract it (on Windows: right-click the file, **Extract All…**). You get a folder
       `migrax-<version>` containing `install.ps1`, `install.sh`, `bin` and `lib`.

    !!! warning "Download the `.zip`, not a `.jar`"

        The Download page also lists `.jar` files. Those are libraries for build tools and
        frameworks (the Maven plugin, the Gradle plugin, the startup integrations); they don't
        contain the command line tool or the installer. Only the ZIP does.

=== "From source"

    ```bash
    git clone https://github.com/fsmutimeer/migrax.git
    cd migrax
    mvn clean install
    ```

    This also installs the Maven plugin and the libraries the integrations use into your local
    Maven repository.

## 2. Run the installer

Open a terminal **in the extracted folder** (or the source folder) and run the installer:

=== "Windows"

    In Command Prompt or PowerShell, for example with the folder extracted to `D:\`:

    ```bat
    cd /d D:\migrax-0.11.0
    powershell -ExecutionPolicy Bypass -File install.ps1
    ```

    (In PowerShell, use `cd D:\migrax-0.11.0`.) The installer copies Migrax to
    `%LOCALAPPDATA%\migrax` and adds its `bin` folder to your user `PATH`. After that you can
    delete the extracted folder and the ZIP.

=== "macOS and Linux"

    ```bash
    sh install.sh
    ```

    Installs to `~/.local/share/migrax` and links `~/.local/bin/migrax`. Set
    `MIGRAX_INSTALL_DIR` and `MIGRAX_BIN_DIR` to choose other folders.

## 3. Check it

**Close the terminal and open a new one**, then:

```console
$ migrax version
migrax 0.11.0
```

!!! tip "`migrax` is not recognized?"

    A terminal reads `PATH` once, when it starts, so a window that was open while the installer
    ran doesn't see the new entry: open a new terminal. Terminals inside an editor (VS Code,
    IntelliJ IDEA) copy the editor's `PATH` from when the editor started, so close every editor
    window and open it again.

    To keep using the current window instead:

    === "Command Prompt"

        ```bat
        set "PATH=%PATH%;%LOCALAPPDATA%\migrax\bin"
        ```

    === "PowerShell"

        ```powershell
        $env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')
        ```

    The `D:\>` prompt is Command Prompt; `PS D:\>` is PowerShell. Their commands differ.

## In a container

To run migrations in Kubernetes or a CI job without installing anything, use the container
image `ghcr.io/fsmutimeer/migrax` (from Migrax 0.3.0); see
[Containers and Kubernetes](../guides/containers-and-kubernetes.md).

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

With Homebrew: `brew upgrade migrax` and `brew uninstall migrax`. With Scoop: `scoop update
migrax` and `scoop uninstall migrax`.

Otherwise, run the installer of the new version again to update. To uninstall, delete the install folder
(`%LOCALAPPDATA%\migrax` on Windows, `~/.local/share/migrax` and the `~/.local/bin/migrax` link
on macOS and Linux) and remove its `bin` folder from your user `PATH`.

Next: [Quick start](quickstart.md).
