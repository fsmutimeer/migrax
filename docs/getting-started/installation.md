# Installation

Migrax is a command line tool. It needs **Java 17 or newer**; nothing else has to be installed,
and no administrator rights are needed.

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
    cd /d D:\migrax-0.1.0
    powershell -ExecutionPolicy Bypass -File install.ps1
    ```

    (In PowerShell, use `cd D:\migrax-0.1.0`.) The installer copies Migrax to
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
migrax 0.1.1
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
(`%LOCALAPPDATA%\migrax` on Windows, `~/.local/share/migrax` and the `~/.local/bin/migrax` link
on macOS and Linux) and remove its `bin` folder from your user `PATH`.

Next: [Quick start](quickstart.md).
