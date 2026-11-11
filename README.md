# QuizMate · 自考刷题与知识点学习

一个跑在你自己电脑上的自考复习工具：真题导入 → 刷题 → 判分 → 错题 → 间隔复习。
默认只监听本机；需要手机访问时用 `start-lan.bat` 开启局域网模式（需要登录，但只建议在自家 Wi-Fi 使用）。

- 技术栈：Java 21 + Spring Boot 3.3 + SQLite ／ Vue 3 + Vite
- 数据位置：`data/`（账号库 `auth.db`；1 号账号沿用 `data/quiz.db`，其他账号使用 `data/users/u<id>/quiz.db`）。
  备份时请停掉服务后复制整个 `data/` 和 `raw/`，不要只复制单个数据库文件。
- 详细设计：`docs/设计方案.md`、`docs/资料盘点与导入策略.md`
- GitHub 上传与后续更新：`docs/GitHub上传指南.md`

> 仓库只保存源码、配置模板和文档；`data/`、`raw/`、数据库、日志和构建产物均为本地文件，已通过 `.gitignore` 排除。

---

## 快速开始

### 0. 前置

需要 **JDK 21**（本机已装在 `D:\java\jdk-21`）和 Maven。

### 1. 一键启动

双击 `start.bat`，或：

```powershell
java -jar target\quizmate-0.1.0.jar
```

浏览器打开 <http://localhost:8080>。

手机访问：双击 `start-lan.bat`（或先设置 `QUIZMATE_HOST=0.0.0.0` 再启动），
查看电脑局域网 IP（`ipconfig`），手机浏览器打开 `http://192.168.x.x:8080`。
首次可能需要在 Windows 防火墙里放行 8080 端口。
局域网模式需要登录；每个账号有自己的数据库和资料目录，但请只在自己信任的网络使用。

### 2. 首次使用（新流程）

第一次打开会先要求创建管理员账号：

1. **创建账号**：第一个账号是管理员，已有的老题库会自动归到这个账号
2. **选地区 + 专业**：省份、专科/本科、专业名称（内置常见专业，也可以自己填）
3. **挑这次要考的学科**：按专业列出参考科目，勾选本次考期准备考的 2–4 门；没有匹配科目时可以手动添加
4. **定考试日期**：选一个考期或具体日期，支持「不同科目考试日期不一样」

完成后进入首页，会显示**距离考试还有多少天**、本次考期的科目和每门课的倒计时。
以后想改科目或日期，点右上角「📅 考试计划」，或首页「考试计划」按钮即可。

> 老用户升级不会被向导拦住：数据库里已有题目或已经填过考试日期的，会自动跳过向导。
> 之前自动生成的默认学科种子已关闭（需要旧行为时可加 `--quizmate.seed-defaults=true`）。

### 账号与资料目录

- 登录页可以自由注册账号；第一个账号是管理员。
- 每个账号的学科、题库、练习记录、统计、AI 配置和答案图互相独立。
- 所有账号的资料根目录都是 `raw/<用户名>/`。
- 1 号账号沿用旧的 `data/quiz.db`，注册时会把旧 `raw/` 里的资料迁到 `raw/<用户名>/`。
- 其他账号使用 `data/users/u<id>/quiz.db`。
- 导入页只能浏览当前账号自己的 `raw/<用户名>/`，这个目录本身不能直接导入，必须再进入学科/试卷子文件夹。

### 专业目录数据来源

设置专业时，界面会按「省份 + 层次」显示专业目录，并标出数据来源和版本：

- **全国兜底目录**：教育部《高等教育自学考试开考专业清单（2021年）》，本科 177 个、专科 101 个
- **已核对省级官方目录**：
  - 江苏：江苏省教育考试院《面向社会开考专业目录（2024年版）》
  - 上海：上海市教育考试院《开考专业信息汇总表（2026年）》
  - 湖北：湖北省教育考试院《2025年面向社会开考专业汇总表》
  - 浙江：浙江省教育考试院《专业考试计划（2023年，2025年5月更新）》
  - 四川：四川省教育考试院《专业考试计划列表（2025年版）》
  - 山东：山东省教育招生考试院《专业课程设置表（2026版）》
  - 广东：广东省教育考试院《专业调整列表（本、专科）》
  - 河南：《河南省2024年高等教育自学考试专业一览表》（已剔除停考、被合并专业）
  - 北京：北京教育考试院《开考专业信息表（2026年）》
  - 天津：《天津市高等教育自学考试专业名称调整对照表》（津考委高发〔2023〕3号）
  - 山西：山西自学考试信息服务平台《专业计划（新计划）》
  - 广西：广西招生考试院《高等教育自学考试专业考试计划（2027版）》
  - 江西：江西省教育考试院《高等教育自学考试专业考试计划（2026版）》
  - 福建：福建省教育考试院《2026年开考专业理论课程考试安排表》
  - 海南：海南省考试局《开考专业汇编（2025年修订版）》
  - 湖南：湖南省教育考试院《2025年10月报考简章》（附表1、2）
  - 安徽：安徽省教育招生考试院《2026年下半年课程安排表》
  - 陕西：陕西省教育考试院《2026年课程安排》
  - 辽宁：辽宁省招生考试办公室《2024年10月考试课程安排表（开考专业）》
  - 内蒙古：内蒙古自治区教育招生考试中心《2026年10月课程安排》
  - 吉林：吉林省教育考试院《2026年10月开考专业课程安排表》
  - 黑龙江：黑龙江省招生考试信息港《自考频道·专业课程查询》
  - 河北：河北省教育考试院自考系统《开考专业（2026年新计划）》
  - 贵州：贵州省招生考试院《2026年下半年课程考试安排表》
  - 云南：云南省招生考试院《2026年下半年开考专业及课程考试安排》
  - 宁夏：宁夏教育考试院《2026年课程安排表》
  - 新疆：新疆教育考试院《2026年报考简章》（已排除官方停考专业）
  - 青海：青海省教育招生考试院《2026年课程时间安排表》
  - 西藏：西藏自治区教育考试院自考平台《专业计划（2026年10月考期）》

没有省级官方目录的省份，会回退到全国标准目录，并在界面上明确提示「本省官方目录尚未收录，具体开考专业以省考试院为准」，不会把全国目录冒充成本省目录。
目录数据在 `src/main/resources/db/major-catalog.json`，启动时自动同步到 `major_catalog` 表；每条都带来源链接、版本和更新时间。

#### 考试计划课程匹配

选了「省份 + 专业」后，学科列表按官方课程目录带出，不再使用旧的内置参考科目。

目前已收录**完整专业课程设置**的省份：

- 河北、山东、江西
- 云南、西藏、海南
- 浙江、四川
- 上海、江苏、广东
- 天津、新疆、黑龙江
- 北京、湖北、陕西、内蒙古
- 山西、湖南、辽宁、贵州

共 22 个省、982 个专业、16270 门课程。其中内蒙古、河北、山西、辽宁、江西、陕西、黑龙江已覆盖目录内全部专业；贵州 44 个、上海 33 个、湖北 39 个、天津 27 个、湖南 85 个。湖南另有 6 个专业继续执行 2016 版计划（地理科学、材料成型及控制工程、人文地理与城乡规划、金融服务与管理、大数据与会计、视觉传达设计），官方未公开该版完整计划，暂按未收录提示。其余专业同样会显示「本省官方完整课程目录尚未收录」，不会用开考课程凑数。

**专业目录和课程设置是两件事。** 没有拿到官方完整课程设置的省份，专业仍然来自省考试院的开考专业 / 课程安排，用户可以在选中专业后手动添加这次要考的学科；界面不会把某一次考试的开考课程自动当成完整计划。甘肃、重庆没有省级官方目录，会回退到全国标准目录并明确标注，不能当作本省开考专业清单。依据课程安排表整理的目录，可能不含全部历史专业或仍含停考过渡专业，界面会提示以省考试院最新公告为准。

只拿到「本次考试开考课程」的省份（宁夏、青海）暂不参与课程匹配，界面显示「本省官方课程目录尚未收录」，避免把一次考试的几门课当成完整专业计划。

课程数据在 `src/main/resources/db/major-courses.json`，启动时同步到 `major_course` 表，按 `province + level + major_code` 查询。没有收录的省份/专业，界面会明确提示「本省官方课程目录尚未收录」，只保留手动添加，不会再给出可能错误的参考科目。

### 3. 从源码构建

直接双击 `build.bat`，或者手动执行：

```powershell
$env:JAVA_HOME = 'D:\java\jdk-21'
cd frontend
npm install
npm run build

cd ..
if (Test-Path src\main\resources\static\assets) {
  Remove-Item src\main\resources\static\assets -Recurse -Force
}
Copy-Item frontend\dist\* src\main\resources\static -Recurse -Force
mvn clean package
```

> Maven 默认可能使用系统里的 JDK 8，构建前一定要把 `JAVA_HOME` 指向 JDK 21。
> 构建前请先关闭正在运行的 QuizMate，否则 Windows 会锁住 `target\quizmate-0.1.0.jar`。
> 前端用的是 hash 路由（`#/practice/1`），所以不需要额外的服务端路由配置。
> 构建前清空 `static/assets` 是为了避免旧 hash 文件越积越多。

---

## 导入题库

账号资料根目录是 `raw/<用户名>/`，根目录本身不能直接预检或导入；
请在账号目录下按学科/考期建子文件夹，例如 `raw/你的用户名/02326操作系统/2018-10真题/`，
避免把不同学科的资料混到同一门课里。

除了页面里的「导入题库」工作台，也可以直接调接口导入：

```powershell
$body = @{
  path = 'D:\opencode\zikao-quiz\raw\02326操作系统\操作系统真题'
  subjectName = '操作系统'
} | ConvertTo-Json

Invoke-RestMethod -Uri 'http://localhost:8080/api/import/dir' `
  -Method Post -ContentType 'application/json; charset=utf-8' `
  -Body ([Text.Encoding]::UTF8.GetBytes($body))
```

支持 `.doc` / `.docx` / `.pdf` / `.pptx` / `.xlsx` / `.txt` / `.md`。

返回结果里每个文件都有 `parsed / imported / skipped / withoutAnswer`，
可以据此判断哪些资料解析得好、哪些缺答案。

**只导入有答案的题**：缺答案的选择题会以 `NO_ANSWER` 状态入库（不进入刷题池），
在首页点「补答案」逐题补全即可；主观题缺答案则直接跳过。
（`NO_ANSWER` 的题在补完答案后自动变成 `ACTIVE`。）

### 预览解析效果（不落库）

```
GET /api/import/preview?path=<文件绝对路径>&limit=10
```

---

## 接口一览

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/health` | 健康检查（不需要登录） |
| GET | `/api/auth/status` | 是否已有账号 |
| POST | `/api/auth/register` | 注册并登录 |
| POST | `/api/auth/login` | 登录 |
| GET | `/api/auth/me` | 当前账号 |
| POST | `/api/auth/logout` | 退出登录 |
| GET | `/api/majors/catalog?province=&level=` | 省级专业目录；无省级数据时回退全国标准目录 |
| GET | `/api/majors/catalog/coverage` | 已收录的省级官方目录清单 |
| GET | `/api/setup/status` | 是否需要首次设置 |
| POST | `/api/setup/complete` | 保存专业、本次考试学科和考试日期 |
| GET | `/api/exam-plan?majorId=` | 考试计划：按日期分组 + 倒计时 |
| GET | `/api/subjects` | 学科列表 + 题量/已练/正确率 |
| POST | `/api/subjects` | 新建学科 `{name, color, examDate}` |
| PUT | `/api/subjects/{id}` | 修改学科名称/代码/考试日期 |
| DELETE | `/api/subjects/{id}` | 删除学科及其题目 |
| GET | `/api/subjects/{id}/chapters` | 章节列表 |
| GET | `/api/stats/overview` | 首页统计 |
| GET | `/api/stats/report?days=14` | 统计页：汇总 / 每日答题 / 学科正确率 / 题型正确率 |
| POST | `/api/practice/sessions` | 创建练习 `{subjectId, count, type, onlyWrong}`，或组卷 `{types, years, onlyNew, shuffle}` |
| POST | `/api/practice/review?limit=30` | 今日复习：把所有学科到期的选择题拼成一套卷（`subjectId=0`，跨学科） |
| GET | `/api/practice/options?subjectId=` | 组卷器选项：各题型/年份题量、错题数、没做过数 |
| GET | `/api/practice/sessions/{id}` | 恢复练习（断点续答） |
| POST | `/api/practice/sessions/{id}/answers` | 提交一题 |
| POST | `/api/practice/sessions/{id}/finish` | 交卷 |
| GET | `/api/practice/sessions/{id}/result` | 结果页数据 |
| POST | `/api/import/dir` | 同步导入目录（旧接口，自动识别「参考答案」文件并按考期对齐） |
| POST | `/api/import/jobs` | 启动后台导入任务，立即返回任务 ID |
| GET | `/api/import/jobs/{id}` | 查询后台导入进度 / 结果明细 |
| POST | `/api/import/jobs/{id}/cancel` | 取消后台导入任务（当前文件处理完后停止） |
| GET | `/api/import/history` | 按文件夹聚合的导入历史（分页/筛选） |
| POST | `/api/import/rollback-group` | 删除某个文件夹下所有导入批次 |
| GET | `/api/import/browse?path=` | 浏览当前账号的 `raw/<用户名>/` 目录 |
| POST | `/api/import/scan` | 预检目录：解析但不落库 `{subjectId, path}` |
| GET | `/api/import/batches?subjectId=` | 导入批次历史 |
| POST | `/api/import/rollback/{id}` | 回滚一次导入（连带作答记录） |
| GET | `/api/import/preview` | 解析预览 |
| GET/PUT | `/api/ai/config` | AI 接口配置（OpenAI 兼容：DeepSeek / 智谱 / SiliconFlow / Ollama） |
| POST | `/api/ai/test` | 测试 AI 连接 |
| POST | `/api/ai/checks/start` | 开始批量校验 `{subjectId, limit, onlyUnchecked}` |
| GET | `/api/ai/checks/progress` | 校验进度 |
| GET | `/api/ai/checks?subjectId=&status=` | 校验结果（默认只看待处理） |
| POST | `/api/ai/checks/{questionId}/apply` | 采纳 AI 答案（会走答案校验后写库） |
| POST | `/api/ai/checks/{questionId}/ignore` | 忽略这条建议 |
| POST | `/api/ai/check-one` | 补答案时单题判断 `{questionId, answer}`，返回 AI 答案和理由 |
| GET | `/api/cards/summary` | 各学科知识点卡片统计 |
| GET | `/api/cards?subjectId=` | 知识点卡片列表 |
| POST | `/api/cards/{id}/review` | 卡片自评 `{rating: MASTER/VAGUE/NONE}` |
| GET | `/api/questions?subjectId=&type=&status=&q=&page=&size=` | 题库分页搜索（题干/答案/来源） |
| PATCH | `/api/questions/{id}` | 改答案/解析 `{answer, analysis}`，选择题按题型校验 |
| DELETE | `/api/questions/{id}` | 删题（连带作答记录和复习状态） |
| POST | `/api/questions/delete` | 批量删题 `{ids:[...]}` |
| GET | `/api/questions/pending?subjectId=&source=` | 待补答案的选择题（可按试卷） |
| GET | `/api/questions/pending/sources?subjectId=` | 待补答案按试卷分组 |
| POST | `/api/questions/{id}/answer` | 补一个答案 `{answer:"B"}` |
| POST | `/api/questions/batch-answer` | 批量补答案 `{subjectId, source, answers:"22A 23B", fromOne:false}` |
| GET | `/api/answer-images?source=` | 从原始 doc/docx/pdf 抽出「答案扫描页」的图片地址 |
| GET | `/api/answer-images/ocr?source=&subjectId=` | 自动识别答案页里的「题号 + 答案」（Tesseract，两遍交叉验证） |
| GET | `/api/answer-images/{key}/{name}` | 答案图字节（可缓存 7 天） |
| GET | `/api/wrong/summary` | 各学科错题数 |
| GET | `/api/wrong?subjectId=` | 错题列表（最近一次做错的题） |

---

## 目录结构

```
zikao-quiz/
├─ src/main/java/com/quizmate/
│  ├─ domain/      实体（Subject / Question / PracticeSession / AnswerRecord / ReviewState）
│  ├─ mapper/      MyBatis-Plus Mapper
│  ├─ service/     业务（学科、练习、判分、SM-2、统计）
│  ├─ importer/    资料解析（TextExtractor / PaperParser / ImportService）
│  ├─ controller/  REST
│  └─ config/      Web、种子数据
├─ src/main/resources/
│  ├─ db/schema.sql    建表（幂等，每次启动执行）
│  └─ static/          前端构建产物
├─ frontend/       Vue 3 + Vite
├─ raw/<用户名>/   每个账号自己的资料目录
├─ data/           运行时生成：quiz.db
├─ docs/           设计方案 + 原型 + 资料盘点
└─ start.bat
```

---

## 已实现（M1）

- 首次设置向导（地区 / 专业 / 本次考期 / 考试日期）+ 考试倒计时
- 学科管理（按专业挂自己的学科，支持从官方课程目录带出或手动添加）
- 资料导入：doc/docx/pdf/pptx 文本抽取 → 状态机切题 → 去重入库
- 刷题：**只刷选择题**（单选 / 多选 / 判断），即时判分 + 解析 + 答题卡 + 键盘快捷键
- **知识点**：填空题 / 名词解释 / 简答 / 计算 / 论述 → 自动生成记忆卡片，先回想再看答案，三档自评 + SM-2 复习
- **答案对齐**：同目录的「参考答案」文件按考期自动配对，补齐题目答案
- **补答案**：缺答案的选择题以 `NO_ANSWER` 入库；按试卷分组，支持逐题点选，也支持把答案批量贴进整套卷子：
  - 带题号 `22A 23B`（最稳，可以直接照答案图抄全卷，已有答案的题号自动忽略）
  - 勾选「按整卷题号排」后填 `B C A D …`，第 1 个答案就对应第 1 题
  - 不勾时按待补列表的顺序填 `A B C AB D`
- **答案扫描图**：不少真题的答案只存在于文档内嵌的扫描图片里。补答案页会自动把原文档里的图片抽出来
  直接显示（点图放大），左边看图、右边粘贴，几十秒就能补完一套卷子；题目侧也补了「题号」字段，
  可以和答案页上的题号一一对应
- **自动识别答案**：切到「批量粘贴」时会自动对答案图跑 OCR（Tesseract，只认数字和 A-D），
  按版面网格推出题号，把识别结果直接填进输入框，人工核对后一键填入。
  两种版面分析（PSM 6 / 4）各跑一遍、只保留一致结果，识别不出的留空而不是瞎猜。
  逐题补模式下，如果这题识别出了答案，选完会立刻告诉你是对是错，不一致时让你决定填哪个
- **错题本**：最近一次做错的题自动进入，答对后自动移出；可一键「刷这些错题」
- **学习统计**：累计答题 / 正确率 / 学习时长 / 连续打卡，近 7/14/30 天答题量柱状图，
  各学科正确率、题型正确率、题库概览
- **组卷器**：按学科 + 题型（多选）+ 年份（多选）+ 范围（全部 / 只练错题 / 只练没做过）+ 题量
  自己拼一套卷子，实时显示预计可组多少题
- **题库**：按学科 / 题型 / 状态（有答案、待补）/ 关键词分页搜索，点开看题干、选项、答案和解析，
  可直接改答案和解析（选择题自动校验格式），也能删掉导入错的脏题
- **今日复习**：按 SM-2 把到期（`due_at <= now`）的选择题拼成一套跨学科复习卷，首页「选择题待复习」
  数字可直接点开；答完自动排下一次复习时间
- **模拟考试**：选学科 + 题量 + 时长（默认 150 分钟），限时倒计时，到点自动交卷；答题过程中不显示
  对错和解析，可以标记不确定的题、跳回去改，交卷后统一给分和及格判定
- **导入工作台**：在页面里浏览当前账号的 `raw/<用户名>/` 目录 → 预检（解析但不入库，看清每份文件能出多少题、多少待补、
  多少是扫描件）→ 一键导入 → 查看导入批次历史，导错了可以整批回滚（连带作答记录）。
  导入在后台线程执行，同一时间只允许一个任务；页面实时显示文件进度，可以继续浏览其他页面，也可以取消；
  刷新页面后任务继续运行。
- **AI 校验答案**：配置任意 OpenAI 兼容接口（DeepSeek / 智谱 GLM-4-Flash / SiliconFlow / 本地 Ollama），
  让 AI 独立做题并与题库答案比对；只把「不一致」作为建议列出来，逐条采纳或忽略，不会自动改答案。
  补答案页面也能用：没有答案图时，选完选项由 AI 判断对错，选错会弹出「AI 认为 B」让你决定
- 结果页（正确率、错题列表）
- SM-2 间隔重复状态（`review_state`，题目与知识点共用）
- 桌面 / 手机自适应

## 下一步

- M2：章节树、资料库
- M3：学完即测（按章节出题）
- M4：打包 exe、局域网二维码、备份恢复

## 已知限制

- 大量老试卷的答案只存在于扫描图片里（文件名写「答案在试卷后」，但文字层没有答案）。
  现在补答案页会自动抽取并显示这些图片，还能用 Tesseract 自动识别（两遍交叉验证）。
  但 OCR 做不到 100%：水印重的页会漏字或读错，所以识别结果只作为「草稿」填进输入框，
  必须对照答案图核对后再落库。另外有一部分卷子的内嵌图片其实是**题目页**而不是答案页，
  这类卷子识别不出答案，只能逐题补或另找资料。
- 主观题不自动判分，按「自评」计分。
- 扫描版 PDF 暂不支持文字解析，但答案页图片仍可抽出显示。

