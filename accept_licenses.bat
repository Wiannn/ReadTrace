@echo off
setlocal
set JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot
set PATH=C:\Program Files\nodejs;%PATH%

for /l %%i in (1,1,14) do (
    echo y
) | "%LOCALAPPDATA%\Android\Sdk\cmdline-tools\cmdline-tools\bin\sdkmanager.bat" "--licenses" "--sdk_root=%LOCALAPPDATA%\Android\Sdk"
