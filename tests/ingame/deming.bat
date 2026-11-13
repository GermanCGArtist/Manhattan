@echo off
rem In-game tests with Deming as the launcher/supervisor of every game (parallel, one Deming run per game).
rem   tests\ingame\deming.bat [test names] [-j N] [--test-timeout S]
rem Does not build: it uses the jars already in build\libs (run build.bat first when the mod changed).
rem Writes tmp\ingame\deming\results.json. Old path: tests\ingame\run.bat. See docs\TESTING.md.
setlocal
python -u "%~dp0run_tests.py" --deming %*
exit /b %errorlevel%
