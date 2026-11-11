package com.zhiya.preschool.admin.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface IdiomAdminMapper {
    String ENTRY_COLUMNS = "id, term, source_pinyin, reviewed_pinyin, child_meaning, difficulty, review_status";

    String ENTRY_FILTER = """
        FROM idiom_entry
        WHERE is_four_character = TRUE
        <if test="keyword != ''">
          AND LOCATE(#{keyword}, term) > 0
        </if>
        <if test="difficulty != null">
          AND difficulty = #{difficulty}
        </if>
        """;

    @Select("<script> SELECT " + ENTRY_COLUMNS + " " + ENTRY_FILTER + """
        ORDER BY id
        LIMIT #{limit} OFFSET #{offset}
        </script>
        """)
    List<Entry> search(
        @Param("keyword") String keyword,
        @Param("difficulty") Integer difficulty,
        @Param("limit") int limit,
        @Param("offset") int offset
    );

    @Select("<script> SELECT COUNT(*) " + ENTRY_FILTER + "</script>")
    long countEntries(@Param("keyword") String keyword, @Param("difficulty") Integer difficulty);

    @Select("SELECT " + ENTRY_COLUMNS + " FROM idiom_entry WHERE id = #{id} AND is_four_character = TRUE")
    Entry findEntry(long id);

    @Select("SELECT " + ENTRY_COLUMNS + " " + """
         FROM idiom_entry
         WHERE is_four_character = TRUE AND difficulty = #{difficulty}
           AND (#{approvedOnly} = FALSE OR review_status = 'APPROVED')
         ORDER BY id
         """)
    List<Entry> findBatchEntries(@Param("difficulty") int difficulty,
                                 @Param("approvedOnly") boolean approvedOnly);

    @Select("""
        SELECT DISTINCT q.source_text
        FROM question q
        JOIN level_question lq ON lq.question_id = q.id
        JOIN level l ON l.id = lq.level_id
        WHERE l.module_id = 1 AND l.status = 'PUBLISHED'
        """)
    List<String> findPublishedTerms();

    @Select("SELECT COUNT(*) FROM level WHERE module_id = 1 AND status = 'PUBLISHED'")
    long countPublishedLevels();

    @Select("""
        SELECT level_no, title
        FROM level
        WHERE module_id = 1 AND status = 'PUBLISHED'
        ORDER BY level_no DESC
        LIMIT 10 OFFSET #{offset}
        """)
    List<LevelSummary> findPublishedLevels(int offset);

    @Select("SELECT id FROM learning_module WHERE id = 1 FOR UPDATE")
    Long lockModule();

    @Select("SELECT COALESCE(MAX(level_no), 0) + 1 FROM level WHERE module_id = 1")
    int nextLevelNo();

    @Insert("INSERT INTO level (module_id, level_no, title, is_free) VALUES (1, #{levelNo}, #{title}, #{free})")
    @Options(useGeneratedKeys = true, keyProperty = "key.id")
    void insertLevel(
        @Param("key") GeneratedId key,
        @Param("levelNo") int levelNo,
        @Param("title") String title,
        @Param("free") boolean free
    );

    @Update("""
        UPDATE idiom_entry
        SET reviewed_pinyin = #{pinyin}, child_meaning = #{meaning}, difficulty = #{difficulty},
            review_status = 'APPROVED'
        WHERE id = #{id}
        """)
    void approve(@Param("id") long id, @Param("pinyin") String pinyin,
                 @Param("meaning") String meaning, @Param("difficulty") int difficulty);

    @Insert("""
        INSERT INTO question (
            module_id, source_text, prompt_text, prompt_pinyin, full_pinyin,
            answer_text, answer_pinyin, meaning, difficulty
        )
        VALUES (
            1, #{question.term}, #{question.prompt}, #{question.promptPinyin}, #{question.pinyin},
            #{question.answer}, #{question.answerPinyin}, #{question.meaning}, #{question.difficulty}
        )
        """)
    @Options(useGeneratedKeys = true, keyProperty = "key.id")
    void insertQuestion(@Param("key") GeneratedId key, @Param("question") Question question);

    @Insert("""
        INSERT INTO level_question (level_id, question_id, sort_order)
        VALUES (#{levelId}, #{questionId}, #{sortOrder})
        """)
    void linkQuestion(@Param("levelId") long levelId, @Param("questionId") long questionId,
                      @Param("sortOrder") int sortOrder);

    record Entry(long id, String term, String sourcePinyin, String reviewedPinyin,
                 String childMeaning, Integer difficulty, String reviewStatus) {
    }

    record LevelSummary(int levelNo, String title) {
    }

    record Question(long entryId, String term, String prompt, String promptPinyin, String pinyin,
                    String answer, String answerPinyin, String meaning, int difficulty) {
    }

    class GeneratedId {
        private Long id;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }
    }
}
