package com.zhiya.preschool.admin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.zhiya.preschool.admin.mapper.IdiomAdminMapper.Question;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class IdiomMeaningGeneratorTest {
    @Test
    void retriesTemporaryDeepSeekFailureAndKeepsTheSuccessfulResult() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        var attempts = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try (exchange) {
                if (attempts.incrementAndGet() == 1) {
                    exchange.sendResponseHeaders(503, -1);
                    return;
                }
                byte[] response = completion(json, "{\"items\":[{\"entryId\":1,\"meaning\":\"非常高兴。\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions");
            var generator = new IdiomMeaningGenerator(endpoint, "test-key", "test-model", json);
            assertEquals(Map.of(1L, "非常高兴。"), generator.generate(List.of(question(1))));
            assertEquals(2, attempts.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void readsOnlyRequestedNonEmptyMeaningsFromResponse() {
        JsonMapper json = JsonMapper.builder().build();
        var generator = new IdiomMeaningGenerator(URI.create("https://api.deepseek.com/chat/completions"),
            "", "test-model", json);
        String modelText = """
            {"items":[{"entryId":1,"meaning":"  一心一意地做一件事。  "},
                      {"entryId":2,"meaning":""},{"entryId":9,"meaning":"额外的词。"}]}
            """;
        String response = completion(json, modelText);
        assertEquals(Map.of(1L, "一心一意地做一件事。"),
            generator.readMeanings(response, List.of(question(1), question(2))));
    }

    private String completion(JsonMapper json, String modelText) {
        return json.writeValueAsString(Map.of(
            "choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", modelText)))
        ));
    }

    private Question question(long id) {
        return new Question(id, "测试词条", "", "", "", "", "", "", 1);
    }
}
