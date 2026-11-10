package com.zhiya.preschool.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 读写成语关卡、题库和本地答题记录。 */
@Mapper
public interface GameMapper {

    @Select("""
        SELECT id, level_no AS levelNo, title
        FROM level
        WHERE module_id = 1
          AND status = 'PUBLISHED'
        ORDER BY level_no
        """)
    List<LevelRow> findPublishedLevels();

    @Select("""
        SELECT id, level_no AS levelNo, title
        FROM level
        WHERE module_id = 1
          AND level_no = #{levelNo}
          AND status = 'PUBLISHED'
        """)
    LevelRow findLevel(int levelNo);

    @Select("""
        SELECT id, level_no AS levelNo, title
        FROM level
        WHERE id = #{id}
        """)
    LevelRow findLevelById(long id);

    @Select("""
        SELECT MIN(candidate.level_no)
        FROM level current_level
        JOIN level candidate ON candidate.module_id = current_level.module_id
        WHERE current_level.id = #{levelId}
          AND candidate.status = 'PUBLISHED'
          AND candidate.level_no > current_level.level_no
        """)
    Integer findNextPublishedLevelNo(long levelId);

    /** 在事务内锁定成语模块行，让续关和提交按顺序更新进度。 */
    @Select("SELECT id FROM learning_module WHERE id = 1 FOR UPDATE")
    Long lockIdiomModule();

    /** 找出最早没有已提交回合的关卡，允许从旧试玩记录中的缺口继续。 */
    @Select("""
        SELECT l.id, l.level_no AS levelNo, l.title
        FROM level l
        WHERE l.module_id = 1
          AND l.status = 'PUBLISHED'
          AND NOT EXISTS (
              SELECT 1 FROM answer_session s
              WHERE s.level_id = l.id
                AND s.local_learner_id = #{learnerId}
                AND s.status = 'COMPLETED'
          )
        ORDER BY l.level_no
        LIMIT 1
        """)
    LevelRow findCurrentLevel(String learnerId);

    /** 查找当前关卡最近一次未提交的回合，复用它的题目和字块。 */
    @Select("""
        SELECT id FROM answer_session
        WHERE level_id = #{levelId}
          AND local_learner_id = #{learnerId}
          AND status = 'IN_PROGRESS'
        ORDER BY id DESC
        LIMIT 1
        """)
    Long findUnfinishedSessionId(@Param("levelId") long levelId, @Param("learnerId") String learnerId);

    @Select("""
        SELECT q.id,
               q.prompt_text AS promptText,
               q.prompt_pinyin AS promptPinyin,
               q.source_text AS sourceText,
               q.full_pinyin AS fullPinyin,
               q.answer_text AS answerText,
               q.answer_pinyin AS answerPinyin,
               q.meaning
        FROM level_question lq
        JOIN question q ON q.id = lq.question_id
        WHERE lq.level_id = #{levelId}
          AND q.status = 'PUBLISHED'
        ORDER BY lq.sort_order
        """)
    List<QuestionRow> findLevelQuestions(long levelId);

    /**
     * 每道题只看最近一次提交。若上一次仍答错且发生在更早的关卡，
     * 则可以安排复习。跳过当前关卡已有的题，避免同一回合出两次。
     */
    @Select("""
        WITH latest_answers AS (
            SELECT sq.question_id, sq.is_correct, l.level_no, s.submitted_at,
                   ROW_NUMBER() OVER (
                       PARTITION BY sq.question_id
                       ORDER BY s.submitted_at DESC, s.id DESC
                   ) AS answer_rank
            FROM answer_session s
            JOIN session_question sq ON sq.session_id = s.id
            JOIN level l ON l.id = s.level_id
            WHERE s.local_learner_id = #{learnerId}
              AND s.status = 'COMPLETED'
              AND l.module_id = 1
        )
        SELECT q.id, q.prompt_text, q.prompt_pinyin, q.source_text,
               q.full_pinyin, q.answer_text, q.answer_pinyin, q.meaning
        FROM latest_answers a
        JOIN question q ON q.id = a.question_id
        WHERE a.answer_rank = 1
          AND a.is_correct = FALSE
          AND a.level_no < #{levelNo}
          AND q.status = 'PUBLISHED'
          AND NOT EXISTS (
              SELECT 1 FROM level_question lq
              WHERE lq.level_id = #{levelId} AND lq.question_id = q.id
          )
        ORDER BY a.submitted_at, q.id
        LIMIT 1
        """)
    QuestionRow findReviewQuestion(
        @Param("learnerId") String learnerId,
        @Param("levelNo") int levelNo,
        @Param("levelId") long levelId
    );

    /** 创建答题回合，数据库生成的 ID 会回填到参数对象。 */
    @Insert("""
        INSERT INTO answer_session (level_id, local_learner_id)
        VALUES (#{levelId}, #{learnerId,jdbcType=VARCHAR})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertSession(SessionInsert row);

    @Insert("""
        INSERT INTO session_question (session_id, question_id, sort_order)
        VALUES (#{sessionId}, #{questionId}, #{sortOrder})
        """)
    void insertSessionQuestion(
        @Param("sessionId") long sessionId,
        @Param("questionId") long questionId,
        @Param("sortOrder") int sortOrder
    );

    @Insert("""
        INSERT INTO session_tile (session_id, tile_no, character_text, pinyin)
        VALUES (#{sessionId}, #{tileNo}, #{characterText}, #{pinyin})
        """)
    void insertTile(
        @Param("sessionId") long sessionId,
        @Param("tileNo") int tileNo,
        @Param("characterText") String characterText,
        @Param("pinyin") String pinyin
    );

    @Select("""
        SELECT id, level_id AS levelId, status, local_learner_id AS learnerId
        FROM answer_session
        WHERE id = #{id}
        """)
    SessionRow findSession(long id);

    /** 提交时锁定回合，确保状态检查和结果写入在同一事务内完成。 */
    @Select("""
        SELECT id, level_id AS levelId, status, local_learner_id AS learnerId
        FROM answer_session
        WHERE id = #{id} FOR UPDATE
        """)
    SessionRow lockSession(long id);

    @Select("""
        SELECT q.id,
               q.prompt_text AS promptText,
               q.prompt_pinyin AS promptPinyin,
               q.source_text AS sourceText,
               q.full_pinyin AS fullPinyin,
               q.answer_text AS answerText,
               q.answer_pinyin AS answerPinyin,
               q.meaning
        FROM session_question sq
        JOIN question q ON q.id = sq.question_id
        WHERE sq.session_id = #{sessionId}
        ORDER BY sq.sort_order
        """)
    List<QuestionRow> findSessionQuestions(long sessionId);

    @Select("""
        SELECT tile_no AS tileNo, character_text AS characterText, pinyin
        FROM session_tile
        WHERE session_id = #{sessionId}
        ORDER BY tile_no
        """)
    List<TileRow> findTiles(long sessionId);

    @Update("""
        UPDATE session_question
        SET selected_tile_no = #{tileNo}, is_correct = #{correct}
        WHERE session_id = #{sessionId}
          AND question_id = #{questionId}
        """)
    void saveAnswer(
        @Param("sessionId") long sessionId,
        @Param("questionId") long questionId,
        @Param("tileNo") int tileNo,
        @Param("correct") boolean correct
    );

    /** 将回合标记为已提交，供进度和错题复习查询使用。 */
    @Update("""
        UPDATE answer_session
        SET status = 'COMPLETED', submitted_at = CURRENT_TIMESTAMP(3)
        WHERE id = #{sessionId}
        """)
    void completeSession(long sessionId);

    record LevelRow(long id, int levelNo, String title) {
    }

    record QuestionRow(
        long id,
        String promptText,
        String promptPinyin,
        String sourceText,
        String fullPinyin,
        String answerText,
        String answerPinyin,
        String meaning
    ) {
    }

    record TileRow(int tileNo, String characterText, String pinyin) {
    }

    record SessionRow(long id, long levelId, String status, String learnerId) {
    }

    class SessionInsert {
        private Long id;
        private final long levelId;
        private final String learnerId;

        public SessionInsert(long levelId, String learnerId) {
            this.levelId = levelId;
            this.learnerId = learnerId;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public long getLevelId() {
            return levelId;
        }

        public String getLearnerId() {
            return learnerId;
        }
    }
}
