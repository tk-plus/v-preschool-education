package com.zhiya.preschool.admin.service;

import com.zhiya.preschool.admin.mapper.IdiomAdminMapper;
import com.zhiya.preschool.admin.mapper.IdiomAdminMapper.Entry;
import com.zhiya.preschool.admin.mapper.IdiomAdminMapper.GeneratedId;
import com.zhiya.preschool.admin.mapper.IdiomAdminMapper.Question;
import com.zhiya.preschool.content.IdiomTiles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IdiomAdminService {
    private static final Pattern SYLLABLE = Pattern.compile("[a-zāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜüńňǹḿ]+");

    private final IdiomAdminMapper mapper;
    private final int freeLevelCount;

    public IdiomAdminService(IdiomAdminMapper mapper, @Value("${game.idiom.free-level-count}") int freeLevelCount) {
        this.mapper = mapper;
        this.freeLevelCount = freeLevelCount;
    }

    @Transactional(readOnly = true)
    public EntryPage search(String keyword, Integer difficulty, int page, int pageSize) {
        String term = keyword.strip();
        int offset = (page - 1) * pageSize;
        long total = mapper.countEntries(term, difficulty);
        List<Entry> rows = offset >= total ? List.of() : mapper.search(term, difficulty, pageSize, offset);
        return new EntryPage(rows, total, (long) offset + pageSize < total);
    }

    @Transactional(readOnly = true)
    public PublishedLevelPage publishedLevels(int page) {
        long total = mapper.countPublishedLevels();
        int offset = (page - 1) * 10;
        List<PublishedLevelSummary> levels = offset >= total ? List.of()
            : mapper.findPublishedLevels(offset).stream()
                .map(level -> new PublishedLevelSummary(level.levelNo(), level.title(), level.levelNo() <= freeLevelCount))
                .toList();
        return new PublishedLevelPage(total, levels, (long) offset + 10 < total);
    }

    public Preview preview(LevelDraft draft) {
        List<Question> questions = prepareQuestions(draft.questions());
        return preview(mapper.nextLevelNo(), questions);
    }

    private Preview preview(int number, List<Question> questions) {
        List<IdiomTiles.Tile> tiles = new ArrayList<>(IdiomTiles.DISTRACTORS);
        for (Question question : questions) {
            tiles.add(new IdiomTiles.Tile(question.answer(), question.answerPinyin()));
        }
        return new Preview(number, number <= freeLevelCount, questions, tiles);
    }

    /** 使用同一事务写入审核结果、题目和关卡，预览后的关卡号也用于拦截重复发布。 */
    @Transactional
    public PublishedLevel publish(LevelDraft draft, int expectedLevelNo) {
        mapper.lockModule();
        int levelNo = mapper.nextLevelNo();
        if (levelNo != expectedLevelNo) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "关卡序号已变化，请重新预览");
        }
        List<Question> questions = prepareQuestions(draft.questions());
        String title = draft.title().strip();
        for (Question question : questions) {
            mapper.approve(question.entryId(), question.pinyin(), question.meaning(), question.difficulty());
        }
        return publishLevel(levelNo, title, questions);
    }

    @Transactional
    public void approveEntry(EntryReview review) {
        mapper.lockModule();
        Entry entry = requireEntry(review.entryId());
        saveReview(entry, review);
    }

    @Transactional
    public void approveBatch(List<EntryReview> reviews) {
        mapper.lockModule();
        var ids = new HashSet<Long>();
        for (EntryReview review : reviews) {
            Entry entry = requireEntry(review.entryId());
            if (!ids.add(entry.id())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "同一个词条只能确认一次");
            }
            if ("APPROVED".equals(entry.reviewStatus()) && entry.reviewedPinyin() != null
                && entry.childMeaning() != null && !entry.childMeaning().isBlank()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "有词条已被审核，请重新生成预览");
            }
            saveReview(entry, review);
        }
    }

    private void saveReview(Entry entry, EntryReview review) {
        String pinyin = String.join(" ", requireSyllables(entry.term(), review.pinyin()));
        mapper.approve(entry.id(), pinyin, review.meaning().strip(), review.difficulty());
    }

    @Transactional(readOnly = true)
    public BatchPreview previewBatch(BatchRequest request) {
        return buildBatch(request);
    }

    /** 锁定模块后重新校验预览，避免并发发布时重复使用词条或改变已预览内容。 */
    @Transactional
    public List<PublishedLevel> publishBatch(BatchRequest request, BatchPreview confirmed) {
        if (!confirmed.pendingEntryIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "这批关卡还有待审核词条，请审核后再发布");
        }
        mapper.lockModule();
        BatchPreview current = buildBatch(request);
        if (!current.equals(confirmed) || current.levels().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "题库或关卡已变化，请重新预览");
        }
        var published = new ArrayList<PublishedLevel>();
        for (BatchLevel level : current.levels()) {
            published.add(publishLevel(level.preview().levelNo(), level.title(), level.preview().questions()));
        }
        return published;
    }

    private BatchPreview buildBatch(BatchRequest request) {
        int nextLevel = mapper.nextLevelNo();
        var publishedTerms = new HashSet<>(mapper.findPublishedTerms());
        var excluded = new HashSet<>(request.excludeEntryIds());
        var forbiddenAnswers = new HashSet<String>();
        for (var tile : IdiomTiles.DISTRACTORS) {
            forbiddenAnswers.add(tile.character());
        }
        var answers = new HashSet<>(forbiddenAnswers);
        var levels = new ArrayList<BatchLevel>();
        var questions = new ArrayList<Question>();
        var pending = new ArrayList<Long>();
        var levelPending = new ArrayList<Long>();
        int skipped = 0;
        boolean approvedOnly = Boolean.TRUE.equals(request.approvedOnly());
        var candidates = new ArrayList<>(mapper.findBatchEntries(request.difficulty(), approvedOnly));
        // 同一次预览的审核、排除和发布沿用原顺序，避免重新抽题。
        long seed = request.shuffleSeed() == null ? nextLevel : request.shuffleSeed();
        Collections.shuffle(candidates, new Random(seed));
        for (Entry entry : candidates) {
            if (publishedTerms.contains(entry.term()) || excluded.contains(entry.id())) {
                continue;
            }
            var letters = entry.term().codePoints().mapToObj(Character::toString).toArray(String[]::new);
            String pinyin = entry.reviewedPinyin() == null ? entry.sourcePinyin() : entry.reviewedPinyin();
            String[] syllables = syllables(pinyin == null ? "" : pinyin);
            String meaning = entry.childMeaning() == null ? "" : entry.childMeaning().strip();
            boolean reviewed = "APPROVED".equals(entry.reviewStatus()) && entry.reviewedPinyin() != null
                && !meaning.isBlank();
            if (letters.length != 4 || !validSyllables(syllables) || (approvedOnly && !reviewed)) {
                skipped++;
                continue;
            }
            int blank = chooseBlank(entry.id(), letters, answers);
            if (blank == -1) {
                skipped++;
                continue;
            }
            Question question = buildQuestion(entry, letters, syllables, blank, meaning, entry.difficulty());
            questions.add(question);
            if (!reviewed) {
                levelPending.add(entry.id());
            }
            answers.add(question.answer());
            if (questions.size() == 3) {
                levels.add(new BatchLevel("成语练习 " + nextLevel, preview(nextLevel, List.copyOf(questions))));
                pending.addAll(levelPending);
                nextLevel++;
                if (levels.size() == request.levelCount()) {
                    break;
                }
                questions.clear();
                levelPending.clear();
                answers = new HashSet<>(forbiddenAnswers);
            }
        }
        return new BatchPreview(levels, skipped, pending);
    }

    private int chooseBlank(long entryId, String[] letters, Set<String> used) {
        for (int offset = 0; offset < letters.length; offset++) {
            int index = (int) ((entryId + offset) % letters.length);
            if (!used.contains(letters[index])) {
                return index;
            }
        }
        return -1;
    }

    private PublishedLevel publishLevel(int levelNo, String title, List<Question> questions) {
        GeneratedId levelKey = new GeneratedId();
        mapper.insertLevel(levelKey, levelNo, title, levelNo <= freeLevelCount);
        for (int index = 0; index < questions.size(); index++) {
            Question question = questions.get(index);
            GeneratedId questionKey = new GeneratedId();
            mapper.insertQuestion(questionKey, question);
            mapper.linkQuestion(levelKey.getId(), questionKey.getId(), index + 1);
        }
        return new PublishedLevel(levelNo, title);
    }

    private List<Question> prepareQuestions(List<QuestionDraft> drafts) {
        var selectedIds = new HashSet<Long>();
        var questions = new ArrayList<Question>();
        for (QuestionDraft draft : drafts) {
            if (!selectedIds.add(draft.entryId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "同一关不能重复选择成语");
            }
            Entry entry = requireEntry(draft.entryId());
            String[] letters = entry.term().codePoints().mapToObj(Character::toString).toArray(String[]::new);
            if (letters.length != 4) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "所选词条不是四字成语");
            }
            questions.add(buildQuestion(entry, letters, requireSyllables(entry.term(), draft.pinyin()),
                draft.blankIndex(), draft.meaning().strip(), draft.difficulty()));
        }
        return questions;
    }

    private Entry requireEntry(long id) {
        Entry entry = mapper.findEntry(id);
        if (entry == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "所选词条不存在或不是四字成语");
        }
        return entry;
    }

    private String[] syllables(String pinyin) {
        return Normalizer.normalize(pinyin.strip(), Normalizer.Form.NFC).split("\\s+");
    }

    private boolean validSyllables(String[] syllables) {
        return syllables.length == 4 && List.of(syllables).stream().allMatch(s -> SYLLABLE.matcher(s).matches());
    }

    private String[] requireSyllables(String term, String pinyin) {
        String[] result = syllables(pinyin);
        if (!validSyllables(result)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, term + "需要填写四个空格分隔的拼音");
        }
        return result;
    }

    private Question buildQuestion(Entry entry, String[] letters, String[] syllables,
                                   int blank, String meaning, int difficulty) {
        String answer = letters[blank];
        String answerPinyin = syllables[blank];
        String fullPinyin = String.join(" ", syllables);
        letters[blank] = "□";
        syllables[blank] = "＿";
        return new Question(entry.id(), entry.term(), String.join("", letters), String.join(" ", syllables),
            fullPinyin, answer, answerPinyin, meaning, difficulty);
    }

    public record EntryReview(
        @Positive long entryId,
        @NotBlank @Size(max = 255) String pinyin,
        @NotBlank @Size(max = 255) String meaning,
        @NotNull @Min(1) @Max(4) Integer difficulty
    ) {
    }

    public record BatchRequest(
        @NotNull @Min(1) @Max(4) Integer difficulty,
        @NotNull @Min(1) @Max(100) Integer levelCount,
        @NotNull @Size(max = 1000) List<@NotNull @Positive Long> excludeEntryIds,
        Boolean approvedOnly,
        Long shuffleSeed
    ) {
    }

    public record BatchLevel(String title, Preview preview) {
    }

    public record BatchPreview(List<BatchLevel> levels, int skipped, List<Long> pendingEntryIds) {
    }

    public record QuestionDraft(
        @Positive long entryId,
        @NotBlank @Size(max = 255) String pinyin,
        @NotBlank @Size(max = 255) String meaning,
        @NotNull @Min(1) @Max(4) Integer difficulty,
        @NotNull @Min(0) @Max(3) Integer blankIndex
    ) {
    }

    public record LevelDraft(
        @NotBlank @Size(max = 64) String title,
        @NotNull @Size(min = 3, max = 3) List<@NotNull @Valid QuestionDraft> questions
    ) {
    }

    public record EntryPage(List<Entry> entries, long total, boolean hasMore) {
    }

    public record Preview(int levelNo, boolean free, List<Question> questions, List<IdiomTiles.Tile> tiles) {
    }

    public record PublishedLevel(int levelNo, String title) {
    }

    public record PublishedLevelSummary(int levelNo, String title, boolean free) {
    }

    public record PublishedLevelPage(long total, List<PublishedLevelSummary> levels, boolean hasMore) {
    }
}
