-- QuizMate schema (SQLite) — idempotent, runs on every start

CREATE TABLE IF NOT EXISTS major (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  code        TEXT,
  name        TEXT NOT NULL,
  color       TEXT DEFAULT '#5B5BE0',
  province    TEXT,
  level       TEXT,
  sort        INTEGER DEFAULT 0,
  created_at  TEXT
);

CREATE TABLE IF NOT EXISTS subject (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  major_id    INTEGER,
  code        TEXT,
  name        TEXT NOT NULL,
  color       TEXT DEFAULT '#4F46E5',
  exam_date   TEXT,
  sort        INTEGER DEFAULT 0,
  created_at  TEXT
);

CREATE TABLE IF NOT EXISTS chapter (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  subject_id  INTEGER NOT NULL,
  parent_id   INTEGER,
  name        TEXT NOT NULL,
  path        TEXT NOT NULL DEFAULT '/',
  depth       INTEGER DEFAULT 0,
  sort        INTEGER DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_chapter_subject ON chapter(subject_id, parent_id);

CREATE TABLE IF NOT EXISTS question (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  subject_id  INTEGER NOT NULL,
  chapter_id  INTEGER,
  type        TEXT NOT NULL,
  stem        TEXT NOT NULL,
  options     TEXT,
  answer      TEXT NOT NULL,
  analysis    TEXT,
  difficulty  INTEGER DEFAULT 3,
  score       REAL DEFAULT 1,
  source      TEXT,
  year        INTEGER,
  tags        TEXT,
  hash        TEXT,
  status      TEXT DEFAULT 'ACTIVE',
  import_batch_id INTEGER,
  created_at  TEXT,
  updated_at  TEXT,
  qno         INTEGER
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_question_hash ON question(hash);
CREATE INDEX IF NOT EXISTS idx_question_subject ON question(subject_id, chapter_id);
CREATE INDEX IF NOT EXISTS idx_question_type ON question(subject_id, type);

CREATE TABLE IF NOT EXISTS practice_session (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id       INTEGER DEFAULT 1,
  subject_id    INTEGER NOT NULL,
  mode          TEXT NOT NULL,
  config        TEXT,
  question_ids  TEXT,
  cursor        INTEGER DEFAULT 0,
  status        TEXT DEFAULT 'DOING',
  correct_count INTEGER DEFAULT 0,
  total_count   INTEGER DEFAULT 0,
  cost_ms       INTEGER DEFAULT 0,
  started_at    TEXT,
  finished_at   TEXT
);
CREATE INDEX IF NOT EXISTS idx_session_subject ON practice_session(subject_id, status);

CREATE TABLE IF NOT EXISTS answer_record (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  session_id   INTEGER NOT NULL,
  user_id      INTEGER DEFAULT 1,
  question_id  INTEGER NOT NULL,
  user_answer  TEXT,
  is_correct   INTEGER,
  score        REAL,
  self_rating  TEXT,
  cost_ms      INTEGER,
  answered_at  TEXT
);
CREATE INDEX IF NOT EXISTS idx_answer_session ON answer_record(session_id);
CREATE INDEX IF NOT EXISTS idx_answer_question ON answer_record(question_id);

CREATE TABLE IF NOT EXISTS review_state (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id       INTEGER DEFAULT 1,
  item_type     TEXT NOT NULL,
  item_id       INTEGER NOT NULL,
  ease          REAL DEFAULT 2.5,
  interval_days INTEGER DEFAULT 0,
  reps          INTEGER DEFAULT 0,
  lapses        INTEGER DEFAULT 0,
  due_at        TEXT,
  last_result   TEXT,
  updated_at    TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_review_item ON review_state(user_id, item_type, item_id);
CREATE INDEX IF NOT EXISTS idx_review_due ON review_state(user_id, item_type, due_at);

CREATE TABLE IF NOT EXISTS import_batch (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  subject_id  INTEGER,
  file_name   TEXT,
  file_path   TEXT,
  parser      TEXT,
  status      TEXT,
  total       INTEGER DEFAULT 0,
  imported    INTEGER DEFAULT 0,
  skipped     INTEGER DEFAULT 0,
  failed      INTEGER DEFAULT 0,
  message     TEXT,
  created_at  TEXT
);

-- AI 答案校验结果（只做建议，采纳后才改 question.answer）
CREATE TABLE IF NOT EXISTS ai_check (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  question_id INTEGER NOT NULL,
  ai_answer   TEXT,
  verdict     TEXT,
  reason      TEXT,
  model       TEXT,
  status      TEXT DEFAULT 'NEW',
  created_at  TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_check_question ON ai_check(question_id);
CREATE INDEX IF NOT EXISTS idx_ai_check_status ON ai_check(status, verdict);

-- 应用级开关：setup_done=1 表示已经完成首次「专业 / 学科 / 考试日期」设置
CREATE TABLE IF NOT EXISTS app_setting (
  key         TEXT PRIMARY KEY,
  value       TEXT,
  updated_at  TEXT
);

-- 省级自考专业目录：province='全国' 存教育部国家标准目录，其余为各省考试院官方目录
CREATE TABLE IF NOT EXISTS major_catalog (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  province    TEXT NOT NULL,
  level       TEXT NOT NULL,
  code        TEXT,
  name        TEXT NOT NULL,
  source_url  TEXT,
  version     TEXT,
  note        TEXT,
  updated_at  TEXT,
  sort        INTEGER DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_major_catalog_lookup ON major_catalog(province, level, sort);
CREATE UNIQUE INDEX IF NOT EXISTS uk_major_catalog ON major_catalog(province, level, code, name);

-- 省级自考专业课程目录：按省份 + 专业代码存官方课程清单
CREATE TABLE IF NOT EXISTS major_course (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  province    TEXT NOT NULL,
  level       TEXT NOT NULL,
  major_code  TEXT NOT NULL,
  major_name  TEXT NOT NULL,
  course_code TEXT,
  course_name TEXT NOT NULL,
  source_url  TEXT,
  version     TEXT,
  note        TEXT,
  updated_at  TEXT,
  sort        INTEGER DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_major_course_lookup ON major_course(province, level, major_code, sort);
