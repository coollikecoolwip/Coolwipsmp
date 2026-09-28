@echo off
setlocal
cd /d "%~dp0"
where java >nul 2>nul
if errorlevel 1 (echo Java 25 or newer is required.&pause&exit /b 1)
where mvn >nul 2>nul
if errorlevel 1 (echo Maven is not installed or is not on PATH.&pause&exit /b 1)
echo Building CoolWips Economy...
call mvn -U clean package
if errorlevel 1 (echo BUILD FAILED.&pause&exit /b 1)
echo BUILD SUCCESSFUL.
echo JAR: target\CoolWipsEconomy-1.0.0.jar
pause
