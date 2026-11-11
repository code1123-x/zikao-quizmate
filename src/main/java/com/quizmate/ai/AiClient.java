package com.quizmate.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quizmate.common.BizException;
import com.quizmate.common.Json;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 通用的 OpenAI 兼容接口客户端（/chat/completions）。
 * DeepSeek、智谱、SiliconFlow、Ollama 都是这个协议。
 */
@Service
public class AiClient {

    /** 正常一次请求的 token 预算（思考模型会先消耗一部分） */
    private static final int MAX_TOKENS = 2000;
    /** content 为空时加大预算重试一次 */
    private static final int RETRY_MAX_TOKENS = 4000;

    private final AiConfigStore store;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public AiClient(AiConfigStore store) {
        this.store = store;
    }

    public String chat(String system, String user) {
        AiConfigStore.Settings s = store.get();
        String key = s.apiKey() == null ? "" : s.apiKey().strip();
        if (key.isEmpty()) {
            if (s.local()) {
                key = "ollama"; // 本地服务不校验 key，随便给一个
            } else {
                throw new BizException("还没有填 API Key，先去 AI 校验页配置");
            }
        }
        if (s.baseUrl() == null || s.baseUrl().isBlank()) {
            throw new BizException("还没有填接口地址");
        }
        String url = s.baseUrl().strip();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (!url.endsWith("/chat/completions")) {
            url += "/chat/completions";
        }
        String model = s.model() == null || s.model().isBlank() ? "deepseek-chat" : s.model();

        BizException last = null;
        // deepseek-flash 这类模型会先「思考」，思考 token 也算在 max_tokens 里；
        // 预算不够时会出现 content 为空、finish_reason=length。加大预算再试一次。
        for (int attempt = 0; attempt < 2; attempt++) {
            int budget = attempt == 0 ? MAX_TOKENS : RETRY_MAX_TOKENS;
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("model", model);
            body.put("temperature", 0);
            body.put("max_tokens", budget);
            body.put("messages", List.of(
                    Map.of("role", "system", "content", system),
                    Map.of("role", "user", "content", user)));
            // DeepSeek 的 flash 默认会先思考；关掉后只输出答案，token 更省、速度更快
            if (!s.thinking() && url.toLowerCase(java.util.Locale.ROOT).contains("deepseek")) {
                body.put("thinking", Map.of("type", "disabled"));
            }
            try {
                HttpResponse<String> resp = post(url, key, body);
                if (resp.statusCode() / 100 != 2) {
                    String err = resp.body() == null ? "" : resp.body();
                    if (resp.statusCode() == 402
                            || err.contains("Insufficient Balance")
                            || err.contains("insufficient_balance")) {
                        throw new BizException("AI 账户余额不足，请先充值，或在 AI 设置里换用其他模型/接口");
                    }
                    throw new BizException("AI 接口返回 " + resp.statusCode() + "：" + trim(err, 200));
                }
                JsonNode root = mapper.readTree(resp.body());
                JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
                String content = contentNode.isMissingNode() ? "" : contentNode.asText("");
                if (!content.isBlank()) {
                    return content;
                }
                String finish = root.path("choices").path(0).path("finish_reason").asText("");
                int reasoning = root.path("usage").path("completion_tokens_details")
                        .path("reasoning_tokens").asInt(0);
                last = new BizException("AI 没有返回内容（finish_reason="
                        + (finish.isBlank() ? "未知" : finish) + "，思考用了 " + reasoning + " tokens）");
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                throw new BizException("调用 AI 失败：" + e.getMessage());
            }
        }
        throw last;
    }

    private HttpResponse<String> post(String url, String key, Map<String, Object> body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(50))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String trim(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
