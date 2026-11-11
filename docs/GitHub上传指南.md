# QuizMate GitHub 上传指南

## 新项目上传

在 GitHub 新建一个空仓库，不要先创建 README、License 或 `.gitignore`，然后在项目根目录执行：

```powershell
cd D:\path\to\your-project
git init -b main
git add .
git commit -m "Initial commit"
git remote add origin https://github.com/用户名/仓库名.git
git push -u origin main
```

## 后续更新

```powershell
git add .
git commit -m "描述本次修改"
git push
```

## 上传前检查

```powershell
git status
git diff --cached --stat
```

确认没有把以下内容加入提交：

- `.env`、API Key、密码和本地配置
- `data/`、`raw/`、数据库和个人资料
- `node_modules/`、`target/`、`dist/`
- 日志、临时压缩包和 IDE 配置

本项目根目录的 `.gitignore` 已经默认排除这些内容。若文件已经被 Git 跟踪，修改 `.gitignore` 不会自动移除它，需要执行：

```powershell
git rm -r --cached data raw frontend/node_modules target frontend/dist
git commit -m "chore: remove local runtime files"
git push
```

## 本项目目录约定

- `src/`：Spring Boot 后端源码
- `frontend/`：Vue 网页端源码
- `miniprogram/`：微信小程序源码
- `docs/`：设计、分析和部署文档
- `data/`：本地运行时数据，不上传
- `raw/`：个人题库资料，不上传

## 常用检查

```powershell
# 网页端
cd frontend
npm install
npm run build

# 后端
cd ..
$env:JAVA_HOME = 'D:\java\jdk-21'
mvn -DskipTests compile
```

