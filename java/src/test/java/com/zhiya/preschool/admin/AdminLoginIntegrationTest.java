package com.zhiya.preschool.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:admin;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "game.idiom.free-level-count=10",
    "admin.login.username=admin",
    "admin.login.password=test-only-admin-password",
    "idiom-ai.api-key="
})
@ActiveProfiles("local")
class AdminLoginIntegrationTest {
    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void requiresLoginAndInvalidatesSessionOnLogout() throws Exception {
        String origin = "http://127.0.0.1:" + port;
        String credentials = "{\"username\":\"admin\",\"password\":\"test-only-admin-password\"}";

        assertEquals(401, get("/api/admin/idioms", null).statusCode());
        assertEquals(403, post("/api/admin/login", credentials, null, "http://localhost:5173").statusCode());
        assertEquals(401, post("/api/admin/login", credentials.replace("test-only-admin-password", "wrong"),
            null, origin).statusCode());
        var login = post("/api/admin/login", credentials, null, origin);
        assertEquals(200, login.statusCode());
        String cookieHeader = login.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(cookieHeader.contains("HttpOnly"));
        assertTrue(cookieHeader.contains("SameSite=Strict"));
        String cookie = cookieHeader.split(";", 2)[0];

        assertEquals(200, get("/api/admin/session", cookie).statusCode());
        assertEquals(200, get("/api/admin/idioms", cookie).statusCode());
        verifyFilteredPagination(cookie);
        verifyBatchPublishing(cookie, origin);
        assertEquals(204, post("/api/admin/access/roles",
            "{\"name\":\"Reader\",\"permissions\":[\"IDIOM_READ\"]}", cookie, origin).statusCode());
        assertEquals(204, post("/api/admin/access/users",
            "{\"username\":\"reader\",\"password\":\"test-reader-password\",\"roleId\":2}", cookie, origin).statusCode());
        var reader = post("/api/admin/login",
            "{\"username\":\"reader\",\"password\":\"test-reader-password\"}", null, origin);
        assertEquals(200, reader.statusCode());
        String readerCookie = reader.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        assertEquals(200, get("/api/admin/idioms", readerCookie).statusCode());
        assertEquals(403, get("/api/admin/access", readerCookie).statusCode());
        assertEquals(403, post("/api/admin/levels/preview", "{}", readerCookie, origin).statusCode());
        assertEquals(400, post("/api/admin/access/users/1",
            "{\"roleId\":2,\"enabled\":true}", cookie, origin).statusCode());
        assertEquals(204, post("/api/admin/access/roles/2",
            "{\"name\":\"Reader\",\"permissions\":[]}", cookie, origin).statusCode());
        assertEquals(403, get("/api/admin/idioms", readerCookie).statusCode());
        assertEquals(204, post("/api/admin/access/users/2/password",
            "{\"password\":\"test-reader-new-password\"}", cookie, origin).statusCode());
        assertEquals(401, get("/api/admin/session", readerCookie).statusCode());
        assertEquals(204, post("/api/admin/logout", "{}", cookie, origin).statusCode());
        assertEquals(401, get("/api/admin/idioms", cookie).statusCode());
        assertEquals(200, get("/api/local/levels", null).statusCode());
    }

    private void verifyBatchPublishing(String cookie, String origin) throws Exception {
        jdbc.update("""
            INSERT INTO idiom_entry (term, source_pinyin, source_name, is_four_character, pinyin_format_ok, difficulty)
            VALUES ('心花怒放', 'xīn huā nù fàng', 'test', TRUE, TRUE, 1)
            """);
        jdbc.update("UPDATE idiom_entry SET source_pinyin = ? WHERE term = ?", "sān xīn èr yì", "三心二意");
        jdbc.update("UPDATE idiom_entry SET source_pinyin = ?, difficulty = 1 WHERE term = ?", "xīn zhào bù xuān", "心照不宣");
        String request = "{\"difficulty\":1,\"levelCount\":2,\"excludeEntryIds\":[],\"shuffleSeed\":12345}";
        var pending = post("/api/admin/levels/batch/preview", request, cookie, origin);
        assertEquals(200, pending.statusCode());
        assertTrue(pending.body().contains("心花怒放"));
        assertTrue(!pending.body().contains("\"pendingEntryIds\":[]"));
        assertEquals(409, post("/api/admin/levels/batch/publish", confirmation(pending.body()), cookie, origin).statusCode());
        var approvedOnly = post("/api/admin/levels/batch/preview",
            request.replace("}", ",\"approvedOnly\":true}"), cookie, origin);
        assertEquals(200, approvedOnly.statusCode());
        assertTrue(approvedOnly.body().contains("\"levels\":[]"));

        approve("一心一意", "yī xīn yī yì", cookie, origin);
        pending = post("/api/admin/levels/batch/preview", request, cookie, origin);
        String batch = confirmation(pending.body());
        String prefix = batch.substring(0, batch.length() - 1);
        long candidateId = jdbc.queryForObject("SELECT id FROM idiom_entry WHERE term = '三心二意'", Long.class);
        assertEquals(503, post("/api/admin/levels/batch/meanings",
            prefix + ",\"entryIds\":[" + candidateId + "]}", cookie, origin).statusCode());
        assertEquals(204, post("/api/admin/levels/batch/review", prefix + ",\"entries\":["
            + review("三心二意", "sān xīn èr yì") + ","
            + review("心照不宣", "xīn zhào bù xuān") + ","
            + review("心花怒放", "xīn huā nù fàng") + "]}", cookie, origin).statusCode());
        var preview = post("/api/admin/levels/batch/preview", request, cookie, origin);
        assertEquals(200, preview.statusCode(), preview.body());
        assertTrue(preview.body().contains("\"levelNo\":12"));
        assertTrue(!preview.body().contains("一心一意"));
        assertTrue(preview.body().contains("心花怒放"));
        assertEquals(11, jdbc.queryForObject("SELECT COUNT(*) FROM level", Integer.class));
        var repeated = post("/api/admin/levels/batch/preview", request, cookie, origin);
        assertEquals(200, repeated.statusCode());
        String tokenField = "\"token\":\"[a-f0-9-]+\"";
        assertEquals(preview.body().replaceFirst(tokenField, ""),
            repeated.body().replaceFirst(tokenField, ""));
        String confirmation = confirmation(repeated.body());
        assertEquals(200, post("/api/admin/levels/batch/publish", confirmation, cookie, origin).statusCode());
        assertEquals(12, jdbc.queryForObject("SELECT COUNT(*) FROM level", Integer.class));
        var published = get("/api/admin/levels/published", cookie);
        assertEquals(200, published.statusCode());
        assertTrue(published.body().contains("\"total\":12"));
        assertTrue(published.body().contains("\"levels\":[{\"levelNo\":12,\"title\":\"成语练习 12\",\"free\":false}"));
        var older = get("/api/admin/levels/published?page=2", cookie);
        assertEquals(200, older.statusCode());
        assertTrue(older.body().contains("\"hasMore\":false"));
        assertTrue(older.body().contains("\"levelNo\":1"));
        assertEquals(409, post("/api/admin/levels/batch/publish", confirmation, cookie, origin).statusCode());
    }

    private String confirmation(String body) {
        var token = Pattern.compile("\"token\":\"([a-f0-9-]+)\"").matcher(body);
        assertTrue(token.find());
        return "{\"token\":\"" + token.group(1) + "\"}";
    }

    private void approve(String term, String pinyin, String cookie, String origin) throws Exception {
        assertEquals(204, post("/api/admin/idioms/review", review(term, pinyin), cookie, origin).statusCode());
    }

    private String review(String term, String pinyin) {
        long id = jdbc.queryForObject("SELECT id FROM idiom_entry WHERE term = ?", Long.class, term);
        return """
            {"entryId":%d,"pinyin":"%s","meaning":"用于测试的释义。","difficulty":1}
            """.formatted(id, pinyin);
    }

    private void verifyFilteredPagination(String cookie) throws Exception {
        jdbc.update("""
            INSERT INTO idiom_entry (term, source_name, is_four_character, pinyin_format_ok, difficulty)
            VALUES ('一心一意', 'test', TRUE, TRUE, 1),
                   ('三心二意', 'test', TRUE, TRUE, 1),
                   ('心照不宣', 'test', TRUE, TRUE, 3),
                   ('心有余而力不足', 'test', FALSE, FALSE, 1),
                   ('五颜六色', 'test', TRUE, TRUE, 1)
            """);
        String query = "/api/admin/idioms?keyword=%E5%BF%83&difficulty=1&pageSize=1";
        var first = get(query, cookie);
        assertEquals(200, first.statusCode());
        assertTrue(first.body().contains("\"total\":2"));
        assertTrue(first.body().contains("\"hasMore\":true"));
        assertTrue(first.body().contains("一心一意"));
        var second = get(query + "&page=2", cookie);
        assertEquals(200, second.statusCode());
        assertTrue(second.body().contains("\"total\":2"));
        assertTrue(second.body().contains("\"hasMore\":false"));
        assertTrue(second.body().contains("三心二意"));
    }

    private HttpResponse<String> get(String path, String cookie) throws IOException, InterruptedException {
        return client.send(request(path, cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String cookie, String origin)
        throws IOException, InterruptedException {
        var request = request(path, cookie)
            .header("Content-Type", "application/json")
            .header("X-Admin-Request", "1")
            .header("Origin", origin)
            .POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path, String cookie) {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        return request;
    }
}
