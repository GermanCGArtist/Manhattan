@echo off
rem Installs Manhattan (the Baritone Script mod, baritonequeue) into the "26.1.1 AFK" profile.
rem The version comes from gradle.properties; build first with build.bat.
rem SurvivalKit, BuildTools and LoadChest are now inside it: their jars are moved out of the mods folder into
rem mods-disabled (not deleted). Run only while Minecraft is closed.
setlocal
set "MODS=C:\Users\PC-Julian\AppData\Roaming\ModrinthApp\profiles\26.1.1 AFK\mods"
set "OFF=C:\Users\PC-Julian\AppData\Roaming\ModrinthApp\profiles\26.1.1 AFK\mods-disabled"
set "JAR=%~dp026.1.1-queue\build\libs\baritonequeue-2.3.8.jar"

powershell -NoProfile -Command "if (Get-CimInstance Win32_Process -Filter \"Name='javaw.exe'\" | Where-Object { $_.CommandLine -like '*26.1.1 AFK*' }) { exit 1 } else { exit 0 }"
if errorlevel 1 (
  echo Minecraft is running with the 26.1.1 AFK profile. Close it first, then run this again.
  exit /b 1
)
if not exist "%JAR%" (
  echo %JAR% is missing. Build it first: build.bat
  exit /b 1
)
if not exist "%OFF%" mkdir "%OFF%"
for %%F in ("%MODS%\survivalkit-*.jar" "%MODS%\buildtools-*.jar" "%MODS%\loadchest-*.jar" "%MODS%\baritonequeue-*.jar") do (
  if exist "%%~F" (
    move /Y "%%~F" "%OFF%\" >nul && echo moved out: %%~nxF
  )
)
copy /Y "%JAR%" "%MODS%\" >nul && echo installed: baritonequeue-%VER%.jar
echo.
echo Now in the mods folder:
dir /B "%MODS%" | findstr /I "baritone survivalkit buildtools loadchest"
endlocal
