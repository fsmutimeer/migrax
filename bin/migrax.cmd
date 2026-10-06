@echo off
setlocal
set "MIGRAX_JAR=%~dp0..\lib\migrax.jar"
if not exist "%MIGRAX_JAR%" set "MIGRAX_JAR=%~dp0..\target\migrax-0.1.0.jar"
if not exist "%MIGRAX_JAR%" (
  echo Migrax CLI JAR not found. Extract the CLI distribution or build the project first. 1>&2
  exit /b 1
)
java -jar "%MIGRAX_JAR%" %*
exit /b %ERRORLEVEL%
