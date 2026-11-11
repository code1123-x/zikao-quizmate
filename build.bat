@echo off
chcp 65001 >nul
cd /d "%~dp0"

if exist "D:\java\jdk-21\bin\java.exe" set "JAVA_HOME=D:\java\jdk-21"
if "%JAVA_HOME%"=="" (
  echo [x] 请先安装 JDK 21，或把 JAVA_HOME 指向 JDK 21。
  pause
  exit /b 1
)

echo 正在构建前端...
pushd frontend
call npm install
call npm run build
popd
if errorlevel 1 (
  echo [x] 前端构建失败
  pause
  exit /b 1
)

echo 正在同步前端产物...
if exist "src\main\resources\static\assets" rmdir /s /q "src\main\resources\static\assets"
xcopy "frontend\dist\*" "src\main\resources\static\" /e /i /y >nul

echo 正在打包后端...
call mvn clean package -DskipTests
if errorlevel 1 (
  echo [x] 后端打包失败
  pause
  exit /b 1
)

echo 构建完成：target\quizmate-0.1.0.jar
pause
