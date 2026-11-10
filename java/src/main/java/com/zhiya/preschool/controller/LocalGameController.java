package com.zhiya.preschool.controller;

import com.zhiya.preschool.exception.GameException;
import com.zhiya.preschool.service.GameService;
import com.zhiya.preschool.service.GameService.Answer;
import com.zhiya.preschool.service.GameService.CurrentGame;
import com.zhiya.preschool.service.GameService.LevelView;
import com.zhiya.preschool.service.GameService.PlaySession;
import com.zhiya.preschool.service.GameService.SubmissionResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 本地试玩接口，仅在启用 local 配置时注册。 */
@Profile("local")
@RestController
@RequestMapping("/api/local")
public class LocalGameController {

    private static final Logger log = LoggerFactory.getLogger(LocalGameController.class);
    private static final String LEARNER_HEADER = "X-Local-Learner-Id";
    private static final Pattern LEARNER_ID = Pattern.compile(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    );

    private final GameService gameService;

    public LocalGameController(GameService gameService) {
        this.gameService = gameService;
    }

    @GetMapping("/levels")
    public List<LevelView> listLevels() {
        return gameService.listLevels();
    }

    /** 分配本地试玩标识，由小程序保存并在后续答题请求中带回。 */
    @PostMapping("/learners")
    public Map<String, String> createLearner() {
        return Map.of("learnerId", UUID.randomUUID().toString());
    }

    /** 从模块首页进入时，返回当前回合、购买解锁提示或已完成状态。 */
    @PostMapping("/sessions/current")
    public CurrentGame resume(@RequestHeader(name = LEARNER_HEADER, required = false) String learnerId) {
        if (learnerId == null || !LEARNER_ID.matcher(learnerId).matches()) {
            throw new GameException(400, "本地试玩标识无效");
        }
        return gameService.resume(learnerId);
    }

    /** 为指定关卡新建回合，支持按关卡调试。 */
    @PostMapping("/levels/{levelNo}/sessions")
    public PlaySession start(
        @PathVariable int levelNo,
        @RequestHeader(name = LEARNER_HEADER, required = false) String learnerId
    ) {
        if (learnerId != null && !LEARNER_ID.matcher(learnerId).matches()) {
            throw new GameException(400, "本地试玩标识无效");
        }
        return gameService.start(levelNo, learnerId);
    }

    /** 读取已创建的回合，复用该回合原有的题目和字块。 */
    @GetMapping("/sessions/{sessionId}")
    public PlaySession get(
        @PathVariable long sessionId,
        @RequestHeader(name = LEARNER_HEADER, required = false) String learnerId
    ) {
        return gameService.get(sessionId, learnerId);
    }

    /** 提交全部空位，返回判题结果和成语讲解。 */
    @PostMapping("/sessions/{sessionId}/submit")
    public SubmissionResult submit(
        @PathVariable long sessionId,
        @RequestBody SubmitRequest request,
        @RequestHeader(name = LEARNER_HEADER, required = false) String learnerId
    ) {
        return gameService.submit(sessionId, request.answers(), learnerId);
    }

    @ExceptionHandler(GameException.class)
    public ResponseEntity<Map<String, String>> handle(GameException exception) {
        log.warn(
            "答题请求未完成，状态码={}，原因={}",
            exception.getStatus(), exception.getMessage()
        );
        return ResponseEntity.status(HttpStatusCode.valueOf(exception.getStatus()))
            .body(Map.of("message", exception.getMessage()));
    }

    /** 将异常详情记录到后台日志，客户端收到统一的错误提示。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception exception) {
        log.error("答题请求发生未预期错误", exception);
        return ResponseEntity.internalServerError()
            .body(Map.of("message", "服务暂时不可用，请稍后重试"));
    }

    public record SubmitRequest(List<Answer> answers) {
    }
}
