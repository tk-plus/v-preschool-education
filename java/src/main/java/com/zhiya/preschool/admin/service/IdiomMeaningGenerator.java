package com.zhiya.preschool.admin.service;

import com.zhiya.preschool.admin.mapper.IdiomAdminMapper.Question;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** 生成儿童版释义草稿，结果由管理员确认后入库。 */
@Service
public class IdiomMeaningGenerator {
    private static final Logger log = LoggerFactory.getLogger(IdiomMeaningGenerator.class);
    private static final int MAX_ATTEMPTS = 3;
    private final URI endpoint;
    private final String key;
    private final String model;
    private final JsonMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public IdiomMeaningGenerator(
        @Value("${idiom-ai.endpoint}") URI endpoint,
        @Value("${idiom-ai.api-key}") String key,
        @Value("${idiom-ai.model}") String model,
        JsonMapper json
    ) {
        this.endpoint = endpoint;
        this.key = key.strip();
        this.model = model;
        this.json = json;
    }

    public Map<Long, String> generate(List<Question> questions) {
        if (key.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "请在 java/config/application-local.yml 中填写 DeepSeek 密钥（idiom-ai.api-key）");
        }
        try {
            var entries = questions.stream()
                .map(question -> Map.of("entryId", question.entryId(), "term", question.term()))
                .toList();
            String body = json.writeValueAsString(Map.of(
                "model", model, "max_tokens", 2400,
                "thinking", Map.of("type", "disabled"),
                "messages", List.of(Map.of("role", "system", "content", """
                    为儿童成语学习编写准确的简体中文释义，每条一到两句，尽量用日常词。
                    保持成语常用含义，必要时说明比喻含义，不编造典故。
                    如果无法确认词义，meaning 返回空字符串。保留每个输入的数字 entryId。
                    只返回 JSON，例如 {"items":[{"entryId":1,"meaning":"形容非常高兴。"}]}。
                    """), Map.of("role", "user", "content", json.writeValueAsString(entries))),
                "response_format", Map.of("type", "json_object")
            ));
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(65))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            return requestMeanings(request, questions);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw upstreamError("释义生成已中断");
        } catch (JacksonException error) {
            throw upstreamError("无法构造释义生成请求");
        }
    }

    /** 仅重试网络异常、临时服务错误和不可用的模型结果。 */
    private Map<Long, String> requestMeanings(HttpRequest request, List<Question> questions)
        throws InterruptedException {
        for (int attempt = 1; ; attempt++) {
            String reason;
            try {
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 200) {
                    try {
                        return readMeanings(response.body(), questions);
                    } catch (JacksonException error) {
                        reason = "DeepSeek 返回的释义格式不正确";
                    } catch (ResponseStatusException error) {
                        reason = error.getReason();
                    }
                } else if (status == 429 || status == 500 || status == 502 || status == 503 || status == 504) {
                    reason = "DeepSeek 暂时不可用（HTTP " + status + "）";
                } else {
                    throw upstreamError("DeepSeek 返回错误（HTTP " + status + "），请检查配置或额度");
                }
            } catch (HttpTimeoutException error) {
                reason = "等待 DeepSeek 响应超时";
            } catch (IOException error) {
                reason = "连接 DeepSeek 时发生网络错误";
                log.debug("DeepSeek 请求异常类型={}", error.getClass().getSimpleName());
            }
            log.warn("释义生成未完成，词条数={}，尝试={}/{}，原因={}", questions.size(), attempt, MAX_ATTEMPTS, reason);
            if (attempt == MAX_ATTEMPTS) {
                throw upstreamError(reason + "。请再次点击生成，继续处理剩余词条。");
            }
            Thread.sleep(Duration.ofSeconds(attempt));
        }
    }

    Map<Long, String> readMeanings(String response, List<Question> questions) {
        var allowed = questions.stream().map(Question::entryId).collect(Collectors.toSet());
        var choice = json.readTree(response).path("choices").path(0);
        if (!"stop".equals(choice.path("finish_reason").asString())) {
            throw upstreamError("释义生成未完成，请重试");
        }
        String content = choice.path("message").path("content").asString("").strip();
        if (content.isBlank()) {
            throw upstreamError("DeepSeek 未返回释义，请重试");
        }
        var items = json.readTree(content).path("items");
        if (!items.isArray()) {
            throw upstreamError("释义结果格式不正确，请重试");
        }
        var meanings = new LinkedHashMap<Long, String>();
        for (var item : items) {
            var id = item.path("entryId");
            var value = item.path("meaning");
            if (!id.isIntegralNumber() || !value.isString()) {
                continue;
            }
            String meaning = value.asString().strip();
            if (allowed.contains(id.asLong()) && !meaning.isBlank() && meaning.length() <= 255) {
                meanings.putIfAbsent(id.asLong(), meaning);
            }
        }
        return meanings;
    }

    private ResponseStatusException upstreamError(String message) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
    }
}
