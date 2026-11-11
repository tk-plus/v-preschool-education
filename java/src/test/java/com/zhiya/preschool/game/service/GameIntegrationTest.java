package com.zhiya.preschool.game.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.zhiya.preschool.game.exception.GameException;
import com.zhiya.preschool.game.service.GameService.Answer;
import com.zhiya.preschool.game.service.GameService.PlaySession;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:game;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "game.idiom.free-level-count=10"
})
class GameIntegrationTest {

    @Autowired
    GameService service;

    @Test
    void migratedSampleLevelCreatesPlayableSession() {
        assertEquals(11, service.listLevels().size());

        var session = service.start(1, null);
        assertNotNull(session.sessionId());
        assertEquals(3, session.questions().size());
        assertEquals(10, session.tiles().size());
        assertFalse(session.questions().getFirst().promptText().contains("采"));

        var result = service.submit(session.sessionId(), answers(session, "采", "意", "色"), null);

        assertEquals(3, result.correctCount());
        assertEquals("COMPLETED", service.get(session.sessionId(), null).status());

        var thirdLevel = service.start(3, null);
        assertEquals(3, thirdLevel.levelNo());
        assertEquals(3, thirdLevel.questions().size());
        assertEquals(10, thirdLevel.tiles().size());
        assertEquals(4, thirdLevel.nextLevelNo());

        var fourthLevel = service.start(thirdLevel.nextLevelNo(), null);
        assertEquals(4, fourthLevel.levelNo());
        assertEquals(5, fourthLevel.nextLevelNo());
        assertEquals(3, fourthLevel.questions().size());
        assertEquals(10, fourthLevel.tiles().size());
    }

    @Test
    void reviewsMissedQuestionInLaterLevelUntilAnsweredCorrectly() {
        String learnerId = UUID.randomUUID().toString();
        String otherLearner = UUID.randomUUID().toString();
        var firstLevel = service.start(1, learnerId);
        long missedId = firstLevel.questions().getFirst().id();
        var wrongAnswers = answers(firstLevel, "彩", "意", "色");
        var error = assertThrows(
            GameException.class,
            () -> service.submit(firstLevel.sessionId(), wrongAnswers, otherLearner)
        );
        assertEquals(404, error.getStatus());
        assertEquals(2, service.submit(firstLevel.sessionId(), wrongAnswers, learnerId).correctCount());

        var secondLevel = service.start(2, learnerId);
        assertEquals(3, secondLevel.questions().size());
        assertEquals(10, secondLevel.tiles().size());
        assertEquals(missedId, secondLevel.questions().getLast().id());
        assertFalse(service.start(2, otherLearner).questions().stream().anyMatch(q -> q.id() == missedId));
        assertEquals(missedId, service.start(3, learnerId).questions().getLast().id());

        var result = service.submit(secondLevel.sessionId(), answers(secondLevel, "了", "点", "采"), learnerId);
        assertEquals(3, result.correctCount());
        assertFalse(service.start(3, learnerId).questions().stream().anyMatch(q -> q.id() == missedId));
    }

    @Test
    void resumesSameSessionAndLocksFirstLevelBeyondFreeLimit() {
        String learnerId = UUID.randomUUID().toString();
        var first = service.resume(learnerId).session();
        assertEquals(first, service.resume(learnerId).session());
        service.submit(first.sessionId(), answers(first, "采", "意", "色"), learnerId);

        var second = service.resume(learnerId).session();
        assertEquals(2, second.levelNo());
        service.submit(second.sessionId(), answers(second, "了", "点", "条"), learnerId);

        var third = service.resume(learnerId).session();
        assertEquals(3, third.levelNo());
        service.submit(third.sessionId(), answers(third, "株", "牢", "恐"), learnerId);

        var fourth = service.resume(learnerId).session();
        assertEquals(4, fourth.levelNo());
        service.submit(fourth.sessionId(), answers(fourth, "口", "添", "惊"), learnerId);

        List<String[]> remainingAnswers = List.of(
            new String[] {"言", "上", "五"},
            new String[] {"舞", "望", "转"},
            new String[] {"假", "琴", "弓"},
            new String[] {"致", "勤", "问"},
            new String[] {"途", "渠", "倍"},
            new String[] {"求", "铃", "竹"}
        );
        for (int index = 0; index < remainingAnswers.size(); index++) {
            var session = service.resume(learnerId).session();
            assertEquals(5 + index, session.levelNo());
            assertEquals(10, session.tiles().size());
            var result = service.submit(session.sessionId(), answers(session, remainingAnswers.get(index)), learnerId);
            assertEquals(3, result.correctCount());
        }

        var locked = service.resume(learnerId);
        assertEquals("LOCKED", locked.status());
        assertEquals(11, locked.levelNo());
        assertNull(locked.session());
        assertEquals(403, assertThrows(GameException.class, () -> service.start(11, learnerId)).getStatus());
    }

    private List<Answer> answers(PlaySession session, String... characters) {
        List<Answer> answers = new ArrayList<>();
        for (int index = 0; index < characters.length; index++) {
            String character = characters[index];
            int tileNo = session.tiles().stream()
                .filter(tile -> tile.characterText().equals(character))
                .findFirst()
                .orElseThrow()
                .tileNo();
            answers.add(new Answer(session.questions().get(index).id(), tileNo));
        }
        return answers;
    }
}
