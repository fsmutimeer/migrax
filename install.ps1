<#
.SYNOPSIS
  Installs the Migrax command line tool for the current Windows user.

.DESCRIPTION
  Run from an extracted Migrax ZIP, or from the Migrax source folder after "mvn package".
  Copies Migrax to %LOCALAPPDATA%\migrax and adds its bin folder to your user PATH,
  so "migrax" works in any new terminal. No administrator rights are needed.

  powershell -ExecutionPolicy Bypass -File install.ps1
#>
param(
  [string]$InstallDir = (Join-Path $env:LOCALAPPDATA 'migrax'),
  # Skip changing the user PATH, for example in CI or when testing an installation.
  [switch]$NoPathUpdate
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$temp = $null

# Find the files to install: an extracted distribution, or a ZIP built into target/.
if (Test-Path (Join-Path $here 'lib\migrax.jar')) {
  $source = $here
} else {
  $zip = Get-ChildItem -Path (Join-Path $here 'target') -Filter 'migrax-*.zip' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $zip) {
    throw "No Migrax build found. Run 'mvn package' in $here first, or run this script from an extracted Migrax ZIP."
  }
  $temp = Join-Path ([IO.Path]::GetTempPath()) ("migrax-install-" + [Guid]::NewGuid())
  Expand-Archive -Path $zip.FullName -DestinationPath $temp
  $source = (Get-ChildItem -Path $temp -Directory | Select-Object -First 1).FullName
}

try {
  # Replace only Migrax's own folders so nothing else in the install folder is touched.
  New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
  foreach ($folder in 'bin', 'lib') {
    $target = Join-Path $InstallDir $folder
    if (Test-Path $target) { Remove-Item -Recurse -Force $target }
    Copy-Item -Recurse -Path (Join-Path $source $folder) -Destination $target
  }
  foreach ($file in 'README.md', 'install.ps1', 'install.sh') {
    $path = Join-Path $source $file
    if (Test-Path $path) { Copy-Item -Force $path $InstallDir }
  }
} finally {
  if ($temp) { Remove-Item -Recurse -Force $temp -ErrorAction SilentlyContinue }
}

$bin = Join-Path $InstallDir 'bin'
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
$entries = @()
if ($userPath) { $entries = $userPath -split ';' | Where-Object { $_ -ne '' } }
if (-not $NoPathUpdate -and $entries -notcontains $bin) {
  [Environment]::SetEnvironmentVariable('Path', (($entries + $bin) -join ';'), 'User')
  Write-Host "Added $bin to your user PATH."
}
# Make "migrax" work in this terminal too; others pick up the user PATH when reopened.
if (-not $NoPathUpdate -and ($env:Path -split ';') -notcontains $bin) {
  $env:Path = "$env:Path;$bin"
}

Write-Host "Installed Migrax to $InstallDir"

# Warn about anything that would shadow the new command.
$existing = Get-Command migrax -ErrorAction SilentlyContinue
if ($existing -and -not $existing.Source.StartsWith($bin, [StringComparison]::OrdinalIgnoreCase)) {
  Write-Warning "Another 'migrax' is earlier on PATH: $($existing.Source). Remove it from PATH to use this installation."
}

$java = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
  Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
  (Get-Command java -ErrorAction SilentlyContinue).Source
}
if (-not $java) {
  Write-Warning 'Java was not found. Migrax needs Java 17 or newer: install a JDK and set JAVA_HOME.'
}

Write-Host ''
Write-Host 'Terminals that were already open do not see the new PATH. Open a new one (for the'
Write-Host 'VS Code or IntelliJ terminal, restart the editor), go to your service folder, and run:'
Write-Host '  migrax init'
Write-Host '  migrax generate'
Write-Host '  migrax migrate'
