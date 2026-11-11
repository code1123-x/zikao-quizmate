package com.quizmate.dto;

import java.util.List;
import java.util.Map;

/**
 * 所有对外 DTO 集中在这里，避免一堆只有两三个字段的小文件。
 */
public final class Dtos {

    private Dtos() {
    }

    /* ---------- 登录 ---------- */

    public record AuthStatus(boolean hasUsers) {
    }

    public record AuthRequest(String username, String password) {
    }

    public record AuthUserView(Long id, String username, boolean admin, String token) {
    }

    /* ---------- 学科 ---------- */

    public record SubjectView(
            Long id,
            String code,
            String name,
            String color,
            String examDate,
            int questionCount,
            int choiceCount,
            int cardCount,
            int pendingCount,
            int answeredCount,
            double accuracy,
            int dueCount
    ) {
    }

    public record CreateSubjectRequest(String name, String code, String color, String examDate, Long majorId) {
    }

    public record UpdateSubjectRequest(String name, String code, String examDate) {
    }

    /* ---------- 专业 ---------- */

    public record MajorView(
            Long id,
            String code,
            String name,
            String color,
            String province,
            String level,
            int subjectCount,
            int questionCount,
            int choiceCount
    ) {
    }

    public record CreateMajorRequest(String name, String code, String color, String province, String level) {
    }

    public record UpdateMajorRequest(String name, String code, String color, String province, String level) {
    }

    /* ---------- 首次设置 / 考试计划 ---------- */

    public record SetupStatus(boolean setupDone, Long majorId, String majorName,
                              String province, String level) {
    }

    public record SetupSubjectItem(Long id, String name, String code, String examDate, Boolean selected) {
    }

    public record SetupCompleteRequest(Long majorId, String majorName, String majorCode,
                                       String province, String level,
                                       List<SetupSubjectItem> subjects) {
    }

    public record SetupCompleteResult(Long majorId, int created, int updated, int cleared) {
    }

    public record PlanGroup(String examDate, long daysLeft, List<SubjectView> subjects) {
    }

    public record ExamPlan(String nextExamDate, Long daysLeft, int plannedCount, int totalSubjectCount,
                           List<PlanGroup> groups, List<SubjectView> unplanned) {
    }

    /* ---------- 省级专业目录 ---------- */

    public record MajorCatalogItem(String code, String name) {
    }

    public record MajorCatalogView(
            String province,
            String level,
            String scope,
            String sourceUrl,
            String version,
            String note,
            String updatedAt,
            int count,
            List<MajorCatalogItem> items
    ) {
    }

    public record MajorCatalogCoverage(
            String province,
            int count,
            String version,
            String updatedAt,
            List<String> levels
    ) {
    }

    /* ---------- 省级专业课程 ---------- */

    public record MajorCourseItem(String code, String name) {
    }

    public record MajorCourseView(
            String province,
            String level,
            String majorCode,
            String majorName,
            String scope,
            String sourceUrl,
            String version,
            String note,
            String updatedAt,
            int count,
            List<MajorCourseItem> courses
    ) {
    }

    public record ChapterView(Long id, Long parentId, String name, int depth, int questionCount) {
    }

    /* ---------- 题目 ---------- */

    public record Option(String key, String text) {
    }

    public record QuestionView(
            Long id,
            String type,
            String stem,
            List<Option> options,
            String answer,
            String analysis,
            double score,
            String source,
            Integer year,
            List<String> tags,
            Long chapterId,
            Integer qno,
            String status
    ) {
    }

    public record QuestionPage(List<QuestionView> items, int total, int page, int size) {
    }

    public record QuestionUpdate(String answer, String analysis) {
    }

    public record DeleteQuestionsRequest(List<Long> ids) {
    }

    /* ---------- 练习 ---------- */

    public record CreateSessionRequest(
            Long subjectId,
            String mode,
            Long chapterId,
            String type,
            Integer count,
            Boolean shuffle,
            Boolean onlyWrong,
            java.util.List<String> types,
            java.util.List<Integer> years,
            Boolean onlyNew,
            Integer durationMinutes,
            java.util.List<Long> questionIds
    ) {
    }

    /* ---------- 组卷器 ---------- */

    public record BuildOptions(
            Long subjectId,
            int choiceCount,
            java.util.List<BuildType> types,
            java.util.List<BuildYear> years,
            java.util.List<BuildChapter> chapters,
            int wrongCount,
            int newCount
    ) {
    }

    public record BuildType(String type, int count) {
    }

    public record BuildYear(Integer year, int count) {
    }

    public record BuildChapter(Long id, String name, int count, int wrongCount, int newCount) {
    }

    public record SessionView(
            Long id,
            Long subjectId,
            String subjectName,
            String mode,
            String status,
            int total,
            int answeredCount,
            Map<Long, String> answered,
            List<QuestionView> questions,
            String startedAt,
            int durationMinutes
    ) {
    }

    public record AnswerRequest(Long questionId, String answer, Long costMs, String selfRating) {
    }

    public record AnswerResult(
            boolean correct,
            String correctAnswer,
            String analysis,
            int correctCount,
            int answeredCount
    ) {
    }

    public record QuestionResult(
            Long questionId,
            String stem,
            String type,
            String correctAnswer,
            String userAnswer,
            boolean correct,
            boolean answered,
            String analysis
    ) {
    }

    public record ResultView(
            Long sessionId,
            Long subjectId,
            String subjectName,
            int total,
            int correct,
            int wrong,
            int unanswered,
            double accuracy,
            double earnedScore,
            double totalScore,
            long costMs,
            List<QuestionResult> items,
            String mode,
            int durationMinutes
    ) {
    }

    /* ---------- 统计 ---------- */

    public record Overview(
            int subjectCount,
            int questionCount,
            int choiceCount,
            int cardCount,
            int pendingCount,
            int wrongCount,
            int answeredQuestionCount,
            int todayAnswered,
            double accuracy,
            int dueCount,
            int dueCardCount,
            int streakDays
    ) {
    }

    public record StatsReport(
            StatsOverview overview,
            java.util.List<StatsDaily> daily,
            java.util.List<StatsSubject> subjects,
            java.util.List<StatsType> types
    ) {
    }

    public record StatsOverview(
            int answered,
            int correct,
            int accuracy,
            int minutes,
            int activeDays,
            int streak,
            int todayAnswered,
            int todayCorrect,
            int weekAnswered,
            int choiceCount,
            int answeredQuestions,
            int cardCount,
            int pendingCount,
            int wrongCount
    ) {
    }

    public record StatsDaily(String date, int answered, int correct) {
    }

    public record StatsSubject(
            Long subjectId,
            String name,
            String color,
            int choiceCount,
            int answered,
            int correct,
            int accuracy,
            int pending
    ) {
    }

    public record StatsType(String type, int answered, int correct, int accuracy) {
    }

    /* ---------- 知识点卡片（由主观题/填空题生成） ---------- */

    public record CardView(
            Long id,
            Long subjectId,
            String type,
            String title,
            String answer,
            String analysis,
            String source,
            Integer year,
            int mastery,
            String lastResult
    ) {
    }

    public record CardSummary(
            Long subjectId,
            String name,
            String color,
            int total,
            int due,
            int mastered
    ) {
    }

    public record CardReviewRequest(String rating) {
    }

    public record SetAnswerRequest(String answer) {
    }

    public record PendingSource(String source, Integer year, int count) {
    }

    public record BatchAnswerRequest(Long subjectId, String source, String answers, Boolean fromOne) {
    }

    public record BatchAnswerResult(int filled, int skipped, String message) {
    }

    public record OcrAnswer(int qno, String answer) {
    }

    public record OcrResult(List<OcrAnswer> answers, int count, String message) {
    }

    public record WrongSubject(Long subjectId, String name, String color, int count) {
    }

    public record WrongItem(
            Long id,
            String type,
            String stem,
            List<Option> options,
            String answer,
            String analysis,
            String source,
            Integer year,
            int wrongCount,
            String lastWrongAt
    ) {
    }

    public record WrongPage(
            List<WrongItem> items,
            int total,
            int page,
            int size
    ) {
    }

    public record CardReviewResult(int mastery, String lastResult, String nextDue) {
    }

    /* ---------- 导入 ---------- */

    public record ImportRequest(String path, String subjectName, Long subjectId, Integer year, Long majorId) {
    }

    public record ImportFileResult(
            String file,
            String status,
            int parsed,
            int imported,
            int skipped,
            int withoutAnswer,
            int pending,
            String message
    ) {
    }

    public record ImportResult(
            int files,
            int imported,
            int skipped,
            List<ImportFileResult> details
    ) {
    }

    /* ---------- 导入工作台 ---------- */

    public record FsEntry(String name, String path, boolean dir, long size, String ext, boolean supported) {
    }

    public record FsListing(String root, String path, String parent, List<FsEntry> entries) {
    }

    public record ImportScanRequest(Long subjectId, String path, Integer year) {
    }

    public record ScanFile(
            String name,
            String path,
            int parsed,
            int objective,
            int withAnswer,
            int pending,
            int skipped,
            int duplicates,
            boolean scanned,
            String message,
            java.util.List<String> samples
    ) {
    }

    public record ScanResult(int files, int parsed, int withAnswer, int pending,
                             int scannedFiles, int skipped, List<ScanFile> details) {
    }

    public record ImportBatchView(Long id, Long subjectId, String subjectName,
                                  String fileName, String filePath,
                                  String status, int total, int imported, int skipped, String createdAt) {
    }

    public record ImportHistoryGroup(
            Long subjectId,
            String subjectName,
            String path,
            String fileName,
            String latestStatus,
            String latestAt,
            int batchCount,
            int imported,
            int skipped,
            List<ImportBatchView> batches
    ) {
    }

    public record ImportHistoryPage(
            int page,
            int size,
            int totalGroups,
            int totalBatches,
            List<ImportHistoryGroup> groups
    ) {
    }

    public record ImportRollbackGroupRequest(Long subjectId, String path) {
    }

    /** 后台导入任务进度。id 与 import_batch.id 相同，页面刷新后可以继续查询。 */
    public record ImportJobView(
            Long id,
            Long subjectId,
            String subjectName,
            String path,
            String status,
            int totalFiles,
            int doneFiles,
            String currentFile,
            int imported,
            int skipped,
            int pending,
            String message,
            String startedAt,
            String finishedAt,
            List<ImportFileResult> details
    ) {
    }

    /* ---------- AI 答案校验 ---------- */

    public record AiConfigView(String provider, String baseUrl, String model,
                               boolean hasKey, String apiKeyMasked, boolean thinking, String note) {
    }

    public record AiConfigUpdate(String provider, String baseUrl, String model, String apiKey,
                                 Boolean thinking) {
    }

    public record AiTestResult(boolean ok, String message, String sample) {
    }

    public record AiCheckStartRequest(Long subjectId, Integer limit, Boolean onlyUnchecked) {
    }

    public record AiApplyAllRequest(Long subjectId, List<Long> questionIds) {
    }

    public record AiCheckStartResult(boolean started, int total, String message) {
    }

    public record AiProgress(boolean running, int done, int total, String message) {
    }

    public record AiCheckView(Long questionId, String stem, String type, String answer,
                              String aiAnswer, String verdict, String reason, String model,
                              String status, String createdAt) {
    }

    public record AiCheckOneRequest(Long questionId, String answer) {
    }

    public record AiCheckOneResult(boolean ok, boolean correct, String aiAnswer,
                                   String reason, String message) {
    }

    public record AiAnalysisRequest(Long questionId) {
    }

    public record AiAnalysisResult(String analysis, boolean generated, String message) {
    }

    public record AiApplyAllResult(int applied, int failed, String message) {
    }

    /** 某学科的 AI 校验进度：总题数 / 还没校验过的题数 */
    public record AiSubjectStat(Long subjectId, int total, int unchecked) {
    }
}
