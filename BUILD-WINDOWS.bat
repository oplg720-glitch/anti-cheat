@echo off
setlocal
where java >nul 2>nul || (echo Java 21 or newer is required.& exit /b 1)
where gradle >nul 2>nul || (echo Gradle is required. Install Gradle 8.7+ and add it to PATH.& exit /b 1)
call gradle clean build
if errorlevel 1 exit /b 1
echo.
echo Built: build\libs\AegisAC-Meteor-1.7.2.jar
endlocal
