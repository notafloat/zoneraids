@echo off
rem Zone Raids server launcher for Windows.
rem Finds Java 17, installs Forge on the first run, picks heap size from installed RAM, starts the server.
rem The window never closes on its own: every exit goes through :end, which waits for a key.
setlocal EnableExtensions EnableDelayedExpansion
title Zone Raids server
cd /d "%~dp0"

if not "%CD:AppData\Local\Temp=%"=="%CD%" (
  echo [!] The server is running from inside the zip archive.
  echo     Extract the whole archive to a normal folder, e.g. C:\ZoneRaids-server, and run start-windows.bat there.
  goto :end
)
if not exist forge-version.txt (
  echo [!] forge-version.txt is missing. Run this file from the unpacked ZoneRaids-server folder.
  goto :end
)
set /p FORGE=<forge-version.txt
set "ARGS=libraries\net\minecraftforge\forge\%FORGE%\win_args.txt"

rem ---- Java 17 -------------------------------------------------------------
if defined JAVA (
  call :isjava17 "%JAVA%" || (echo [!] JAVA=%JAVA% is not Java 17. & set "JAVA=")
)
if not defined JAVA call :findjava
if not defined JAVA (
  echo [!] Java 17 was not found.
  echo     Install Temurin 17 ^(JDK or JRE, x64, .msi^) from https://adoptium.net/temurin/releases/?version=17
  echo     and tick "Set JAVA_HOME" / "Add to PATH" in the installer, then run this file again.
  goto :end
)
echo Java: %JAVA%

rem ---- Memory ---------------------------------------------------------------
rem Leave room for Windows and for the game client if it runs on the same PC.
set RAM_GB=0
for /f "usebackq delims=" %%M in (`powershell -NoProfile -Command "[math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory/1GB)" 2^>nul`) do set RAM_GB=%%M
if not defined HEAP (
  set HEAP=3G
  if !RAM_GB! GEQ 12 set HEAP=4G
  if !RAM_GB! GEQ 16 set HEAP=6G
)
echo RAM: !RAM_GB! GB, server heap: !HEAP!  ^(set HEAP=4G before running to override^)

rem ---- Forge ----------------------------------------------------------------
if not exist "%ARGS%" (
  echo Installing Forge %FORGE% ^(first run only, needs internet^)...
  curl -fL --retry 4 -o forge-installer.jar "https://maven.minecraftforge.net/net/minecraftforge/forge/%FORGE%/forge-%FORGE%-installer.jar"
  if errorlevel 1 (echo [!] Could not download Forge. Check the internet connection. & goto :end)
  "%JAVA%" -jar forge-installer.jar --installServer
  if errorlevel 1 (echo [!] Forge installer failed, see forge-installer.jar.log & goto :end)
  del forge-installer.jar forge-installer.jar.log 2>nul
)

rem ---- EULA -----------------------------------------------------------------
findstr /b /c:"eula=true" eula.txt >nul 2>&1
if errorlevel 1 (
  echo.
  echo Minecraft EULA: https://aka.ms/MinecraftEULA
  set /p ACCEPT=Type Y and press Enter to accept the EULA:
  if /i "!ACCEPT!"=="Y" (
    echo eula=true> eula.txt
  ) else (
    echo eula=false> eula.txt
    echo [!] The server cannot start without accepting the EULA.
    goto :end
  )
)

rem ---- Run -------------------------------------------------------------------
echo Starting the server. Type "stop" here to shut it down.
"%JAVA%" -Xms!HEAP! -Xmx!HEAP! @zoneraids_jvm_args.txt @"%ARGS%" nogui %*
echo.
echo Server stopped ^(exit code %errorlevel%^). If it crashed, see logs\latest.log and crash-reports\.
goto :end

rem ---- helpers ---------------------------------------------------------------
:isjava17
"%~1" -version 2>&1 | findstr /r /c:"version .17\." >nul
exit /b %errorlevel%

:findjava
for /f "delims=" %%J in ('where java 2^>nul') do (
  if not defined JAVA call :isjava17 "%%J" && set "JAVA=%%J"
)
if defined JAVA exit /b 0
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" (
  call :isjava17 "%JAVA_HOME%\bin\java.exe" && set "JAVA=%JAVA_HOME%\bin\java.exe" && exit /b 0
)
for %%R in ("%ProgramFiles%\Eclipse Adoptium" "%ProgramFiles%\Java" "%ProgramFiles%\Microsoft" "%ProgramFiles%\Zulu" "%ProgramFiles%\BellSoft" "%APPDATA%\PrismLauncher\java" "%LOCALAPPDATA%\Programs\Eclipse Adoptium") do (
  if exist "%%~R" for /d %%D in ("%%~R\*") do (
    if not defined JAVA if exist "%%~D\bin\java.exe" call :isjava17 "%%~D\bin\java.exe" && set "JAVA=%%~D\bin\java.exe"
  )
)
exit /b 0

:end
echo.
pause
endlocal
