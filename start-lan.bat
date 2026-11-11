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

echo 正在以局域网模式启动 QuizMate...
echo 注意：局域网模式需要登录，但只建议在自家 Wi-Fi 使用。
set "QUIZMATE_HOST=0.0.0.0"

start "quizmate-open" cmd /c "timeout /t 6 >nul && start http://localhost:8080"

"%JAVA_EXE%" -Dfile.encoding=UTF-8 -jar target\quizmate-0.1.0.jar
pause
