@echo off
rem Builds the mod. Output: build\libs\baritonequeue-<version>.jar
rem Uses the portable JDK 25 in tools\jdk-25* if it is there (not in git), otherwise your own JAVA_HOME.
set "ROOT=%~dp0"
for /d %%D in ("%ROOT%tools\jdk-25*") do set "JAVA_HOME=%%~fD"
rem Short temp dir: the JDK's internal loopback pipe fails under long temp paths
if not exist "%ROOT%tmp" mkdir "%ROOT%tmp"
set "TMP=%ROOT%tmp"
set "TEMP=%ROOT%tmp"
set "JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=%ROOT%tmp"
call "%~dp0gradlew.bat" build %*
