@echo off
rem Zone Raids server launcher: installs Forge on the first run, then starts the server.
cd /d "%~dp0"
set /p FORGE=<forge-version.txt
set ARGS=libraries\net\minecraftforge\forge\%FORGE%\win_args.txt
if "%JAVA%"=="" set JAVA=java

"%JAVA%" -version 2>&1 | findstr /c:"version \"17" >nul
if errorlevel 1 (
  echo Java 17 is required ^(Temurin 17^). Set its path: set JAVA=C:\path\to\java.exe
  pause
  exit /b 1
)

if not exist "%ARGS%" (
  echo Installing Forge %FORGE%...
  curl -fL --retry 4 -o forge-installer.jar "https://maven.minecraftforge.net/net/minecraftforge/forge/%FORGE%/forge-%FORGE%-installer.jar" || goto :fail
  "%JAVA%" -jar forge-installer.jar --installServer || goto :fail
  del forge-installer.jar forge-installer.jar.log 2>nul
)

findstr /b /c:"eula=true" eula.txt >nul 2>&1
if errorlevel 1 (
  echo Read https://aka.ms/MinecraftEULA and put eula=true into eula.txt, then run again.
  echo eula=false> eula.txt
  pause
  exit /b 1
)

"%JAVA%" @zoneraids_jvm_args.txt @"%ARGS%" nogui %*
pause
exit /b 0

:fail
echo Forge install failed.
pause
exit /b 1
