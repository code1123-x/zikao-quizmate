@echo off
chcp 65001 >nul
cd /d "%~dp0"

set "JAVA_EXE=java"
if exist "D:\java\jdk-21\bin\java.exe" set "JAVA_EXE=D:\java\jdk-21\bin\java.exe"

if not exist "target\quizmate-0.1.0.jar" (
  echo [x] 没有找到 target\quizmate-0.1.0.jar，请先执行打包：
  echo     mvn -DskipTests package
  pause
  exit /b 1
)

echo 正在以本机模式启动 QuizMate（手机访问请用 start-lan.bat）...
start "quizmate-open" cmd /c "timeout /t 6 >nul && start http://localhost:8080"

"%JAVA_EXE%" -Dfile.encoding=UTF-8 -jar target\quizmate-0.1.0.jar
pause
