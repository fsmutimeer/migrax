@echo off
rem Migrax launcher for Windows (cmd.exe and PowerShell).
setlocal EnableExtensions
set "MIGRAX_HOME=%~dp0.."
set "MIGRAX_JAR=%MIGRAX_HOME%\lib\migrax.jar"
if not exist "%MIGRAX_JAR%" (
  for %%f in ("%MIGRAX_HOME%\target\migrax-*.jar") do set "MIGRAX_JAR=%%~ff"
)
if not exist "%MIGRAX_JAR%" (
  echo migrax: the Migrax jar was not found. Reinstall Migrax, or run "mvn package" in the Migrax source folder. 1>&2
  exit /b 1
)

set "JAVA_EXE=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if "%JAVA_EXE%"=="java" (
  where java >nul 2>&1 || (
    echo migrax: Java 17 or newer is required. Install it, or set JAVA_HOME. 1>&2
    exit /b 1
  )
)

set "JAVA_VERSION="
for /f "tokens=3" %%v in ('call "%JAVA_EXE%" -version 2^>^&1 ^| findstr /i /c:"version"') do if not defined JAVA_VERSION set "JAVA_VERSION=%%~v"
set "JAVA_MAJOR=99"
if defined JAVA_VERSION for /f "delims=." %%m in ("%JAVA_VERSION%") do set "JAVA_MAJOR=%%m"
if %JAVA_MAJOR% LSS 17 (
  echo migrax: Java 17 or newer is required, but "%JAVA_EXE%" is Java %JAVA_VERSION%. Set JAVA_HOME to a newer JDK. 1>&2
  exit /b 1
)

rem Java 19+ keeps a class-data archive of Migrax's classes, which makes later starts faster.
rem The JVM recreates it when Migrax or Java changes. Set MIGRAX_NO_CDS=1 to skip.
if defined MIGRAX_NO_CDS goto run
if not defined JAVA_VERSION goto run
if %JAVA_MAJOR% LSS 19 goto run
if not defined LOCALAPPDATA goto run
if not exist "%LOCALAPPDATA%\migrax\cache" mkdir "%LOCALAPPDATA%\migrax\cache" 2>nul
if not exist "%LOCALAPPDATA%\migrax\cache" goto run
"%JAVA_EXE%" -XX:+AutoCreateSharedArchive -Xlog:cds*=off "-XX:SharedArchiveFile=%LOCALAPPDATA%\migrax\cache\migrax-java%JAVA_MAJOR%.jsa" %MIGRAX_JAVA_OPTS% -jar "%MIGRAX_JAR%" %*
exit /b %ERRORLEVEL%

:run
"%JAVA_EXE%" %MIGRAX_JAVA_OPTS% -jar "%MIGRAX_JAR%" %*
exit /b %ERRORLEVEL%
