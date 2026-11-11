package com.zhiya.preschool.game.service;

import com.zhiya.preschool.content.IdiomTiles;
import com.zhiya.preschool.game.exception.GameException;
import com.zhiya.preschool.game.mapper.GameMapper;
import com.zhiya.preschool.game.mapper.GameMapper.LevelRow;
import com.zhiya.preschool.game.mapper.GameMapper.QuestionRow;
import com.zhiya.preschool.game.mapper.GameMapper.SessionInsert;
import com.zhiya.preschool.game.mapper.GameMapper.SessionRow;
import com.zhiya.preschool.game.mapper.GameMapper.TileRow;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 处理成语填字的关卡进度、出题、错题复习和判分。 */
@Service
public class GameService {

    private static final Logger log = LoggerFactory.getLogger(GameService.class);

    private final GameMapper mapper;
    // 按关卡序号判定免费范围，例如配置为 10 时，第 11 关开始需要购买解锁。
    private final int freeLevelCount;
    private final SecureRandom random = new SecureRandom();

    public GameService(GameMapper mapper, @Value("${game.idiom.free-level-count}") int freeLevelCount) {
        if (freeLevelCount < 0) {
            throw new IllegalArgumentException("成语免费关卡数不能为负数");
        }
        this.mapper = mapper;
        this.freeLevelCount = freeLevelCount;
    }

    /** 返回已发布的关卡摘要，供关卡列表接口使用。 */
    public List<LevelView> listLevels() {
        return mapper.findPublishedLevels()
            .stream()
            .map(level -> new LevelView(level.levelNo(), level.title()))
            .toList();
    }

    /** 为指定的已发布关卡创建一个新回合。 */
    @Transactional
    public PlaySession start(int levelNo, String learnerId) {
        log.debug("准备创建答题回合，关卡序号={}", levelNo);

        LevelRow level = mapper.findLevel(levelNo);
        if (level == null) {
            throw new GameException(404, "关卡不存在");
        }
        requireFreeLevel(level);
        return createSession(level, learnerId);
    }

    /** 从最早未提交的关卡继续，优先复用该关卡已有的答题回合。 */
    @Transactional
    public CurrentGame resume(String learnerId) {
        // 续关与提交共用模块行锁，避免并发请求重复创建回合或读到提交前的进度。
        mapper.lockIdiomModule();
        LevelRow level = mapper.findCurrentLevel(learnerId);
        if (level == null) {
            return new CurrentGame("COMPLETED", null, freeLevelCount, null);
        }
        if (level.levelNo() > freeLevelCount) {
            return new CurrentGame("LOCKED", level.levelNo(), freeLevelCount, null);
        }

        Long sessionId = mapper.findUnfinishedSessionId(level.id(), learnerId);
        PlaySession session = sessionId == null ? createSession(level, learnerId) : get(sessionId, learnerId);
        return new CurrentGame("PLAYING", level.levelNo(), freeLevelCount, session);
    }

    /** 校验直接访问回合的请求也符合免费范围。 */
    private void requireFreeLevel(LevelRow level) {
        if (level.levelNo() > freeLevelCount) {
            throw new GameException(403, "本关需要购买解锁");
        }
    }

    /** 在调用方的事务内保存本回合的题目顺序和字块排列，供退出后继续作答。 */
    private PlaySession createSession(LevelRow level, String learnerId) {
        List<QuestionRow> questions = new ArrayList<>(mapper.findLevelQuestions(level.id()));
        if (questions.size() != 3) {
            throw new GameException(409, "当前关卡尚未配置好 3 道题");
        }

        // 一关最多用一道此前仍未答对的题替换最后一题，保持每关三题。
        QuestionRow reviewQuestion = learnerId == null || level.levelNo() == 1
            ? null
            : mapper.findReviewQuestion(learnerId, level.levelNo(), level.id());
        if (reviewQuestion != null) {
            questions.set(questions.size() - 1, reviewQuestion);
        }

        SessionInsert session = new SessionInsert(level.id(), learnerId);
        mapper.insertSession(session);

        for (int index = 0; index < questions.size(); index++) {
            mapper.insertSessionQuestion(
                session.getId(),
                questions.get(index).id(),
                index + 1
            );
        }

        List<TileRow> tiles = new ArrayList<>();
        for (QuestionRow question : questions) {
            tiles.add(new TileRow(0, question.answerText(), question.answerPinyin()));
        }
        for (var distractor : IdiomTiles.DISTRACTORS) {
            tiles.add(new TileRow(0, distractor.character(), distractor.pinyin()));
        }
        Collections.shuffle(tiles, random);

        // 字块序号以洗牌后的顺序为准，提交时用它识别选中的字块。
        for (int index = 0; index < tiles.size(); index++) {
            TileRow tile = tiles.get(index);
            mapper.insertTile(
                session.getId(),
                index + 1,
                tile.characterText(),
                tile.pinyin()
            );
        }

        PlaySession playSession = get(session.getId(), learnerId);
        log.info(
            "答题回合已创建，回合ID={}，关卡ID={}，题目数={}，字块数={}，包含复习题={}",
            session.getId(), level.id(), questions.size(), tiles.size(), reviewQuestion != null
        );
        return playSession;
    }

    /** 校验回合归属后返回答题内容，题目只包含缺字文本和提示拼音。 */
    public PlaySession get(long sessionId, String learnerId) {
        log.debug("读取答题回合，回合ID={}", sessionId);

        SessionRow session = mapper.findSession(sessionId);
        if (session == null || !Objects.equals(session.learnerId(), learnerId)) {
            throw new GameException(404, "答题回合不存在");
        }

        LevelRow level = mapper.findLevelById(session.levelId());
        requireFreeLevel(level);
        List<QuestionView> questions = mapper.findSessionQuestions(sessionId)
            .stream()
            .map(question -> new QuestionView(
                question.id(),
                question.promptText(),
                question.promptPinyin()
            ))
            .toList();

        return new PlaySession(
            session.id(),
            level.levelNo(),
            mapper.findNextPublishedLevelNo(level.id()),
            level.title(),
            session.status(),
            questions,
            mapper.findTiles(sessionId)
        );
    }

    /** 校验整组答案后统一判分，在同一事务中写入结果并推进关卡进度。 */
    @Transactional
    public SubmissionResult submit(long sessionId, List<Answer> answers, String learnerId) {
        // 与续关保持相同的加锁顺序，再锁定当前回合，防止重复提交。
        mapper.lockIdiomModule();
        SessionRow session = mapper.lockSession(sessionId);
        if (session == null || !Objects.equals(session.learnerId(), learnerId)) {
            throw new GameException(404, "答题回合不存在");
        }
        requireFreeLevel(mapper.findLevelById(session.levelId()));
        if (!"IN_PROGRESS".equals(session.status())) {
            throw new GameException(409, "本关已经提交");
        }

        List<QuestionRow> questions = mapper.findSessionQuestions(sessionId);
        Map<Long, QuestionRow> questionsById = new HashMap<>();
        for (QuestionRow question : questions) {
            questionsById.put(question.id(), question);
        }

        Map<Integer, TileRow> tilesByNumber = new HashMap<>();
        for (TileRow tile : mapper.findTiles(sessionId)) {
            tilesByNumber.put(tile.tileNo(), tile);
        }

        if (answers == null || answers.size() != questions.size()) {
            throw new GameException(400, "请填写全部空位");
        }

        // 只接收当前回合的题目和字块，同一个字块只能填一个空。
        Set<Long> usedQuestions = new HashSet<>();
        Set<Integer> usedTiles = new HashSet<>();
        for (Answer answer : answers) {
            boolean invalidQuestion = !questionsById.containsKey(answer.questionId());
            boolean invalidTile = !tilesByNumber.containsKey(answer.tileNo());
            boolean repeatedQuestion = !usedQuestions.add(answer.questionId());
            boolean repeatedTile = !usedTiles.add(answer.tileNo());

            if (invalidQuestion || invalidTile || repeatedQuestion || repeatedTile) {
                throw new GameException(400, "答案中有无效或重复的题目、字块");
            }
        }

        int correctCount = 0;
        List<AnswerResult> results = new ArrayList<>();
        for (Answer answer : answers) {
            QuestionRow question = questionsById.get(answer.questionId());
            TileRow tile = tilesByNumber.get(answer.tileNo());
            boolean correct = question.answerText().equals(tile.characterText());

            if (correct) {
                correctCount++;
            }

            mapper.saveAnswer(sessionId, question.id(), answer.tileNo(), correct);
            results.add(new AnswerResult(
                question.id(),
                correct,
                question.sourceText(),
                question.fullPinyin(),
                question.meaning()
            ));
        }

        mapper.completeSession(sessionId);
        log.info(
            "答题回合已完成，回合ID={}，正确数={}，总题数={}",
            sessionId, correctCount, questions.size()
        );
        return new SubmissionResult(correctCount, questions.size(), results);
    }

    /** 提交答案前展示的题目。 */
    public record QuestionView(long id, String promptText, String promptPinyin) {
    }

    public record LevelView(int levelNo, String title) {
    }

    /** 续关结果，只有 PLAYING 状态会附带答题回合。 */
    public record CurrentGame(String status, Integer levelNo, int freeLevelCount, PlaySession session) {
    }

    /** 答题回合，nextLevelNo 为空表示当前关卡之后没有已发布的关卡。 */
    public record PlaySession(
        long sessionId,
        int levelNo,
        Integer nextLevelNo,
        String title,
        String status,
        List<QuestionView> questions,
        List<TileRow> tiles
    ) {
    }

    /** 一道题的提交内容，由题目 ID 和选中的字块序号组成。 */
    public record Answer(long questionId, int tileNo) {
    }

    public record SubmissionResult(
        int correctCount,
        int total,
        List<AnswerResult> results
    ) {
    }

    public record AnswerResult(
        long questionId,
        boolean correct,
        String sourceText,
        String fullPinyin,
        String meaning
    ) {
    }
}
