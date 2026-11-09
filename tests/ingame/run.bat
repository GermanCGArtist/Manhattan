@echo off
rem In-game tests: build, install into the "Manhatten Testing" profile, start the game, run the tests, close it.
rem   tests\ingame\run.bat [test names] [--keep-open] [--attach]
rem Never touches any other Minecraft instance. See docs\TESTING.md.
setlocal
set "ROOT=%~dp0..\.."
echo %* | findstr /C:"--attach" >nul
if errorlevel 1 (
  call "%ROOT%\build.bat" || exit /b 1
)
python -u "%~dp0run_tests.py" %*
exit /b %errorlevel%
