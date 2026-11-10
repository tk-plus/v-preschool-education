package com.zhiya.preschool.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zhiya.preschool.exception.GameException;
import com.zhiya.preschool.mapper.GameMapper;
import com.zhiya.preschool.mapper.GameMapper.LevelRow;
import com.zhiya.preschool.mapper.GameMapper.QuestionRow;
import com.zhiya.preschool.mapper.GameMapper.SessionRow;
import com.zhiya.preschool.mapper.GameMapper.TileRow;
import com.zhiya.preschool.service.GameService.Answer;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GameServiceTest {

    private GameMapper mapper;
    private GameService service;

    @BeforeEach
    void setup() {
        mapper = Mockito.mock(GameMapper.class);
        service = new GameService(mapper, 4);
        when(mapper.lockSession(10)).thenReturn(new SessionRow(10, 1, "IN_PROGRESS", null));
        when(mapper.findLevelById(1)).thenReturn(new LevelRow(1, 1, "初识成语"));
        when(mapper.findSessionQuestions(10)).thenReturn(List.of(
            new QuestionRow(
                1, "兴高□烈", "xìng gāo ＿ liè", "兴高采烈",
                "xìng gāo cǎi liè", "采", "cǎi", "非常高兴"
            ),
            new QuestionRow(
                2, "一心一□", "yī xīn yī ＿", "一心一意",
                "yī xīn yī yì", "意", "yì", "专心"
            )
        ));
        when(mapper.findTiles(10)).thenReturn(List.of(
            new TileRow(1, "采", "cǎi"),
            new TileRow(2, "意", "yì")
        ));
    }

    @Test
    void rejectsUsingOneTileForTwoBlanks() {
        GameException error = assertThrows(
            GameException.class,
            () -> service.submit(10, List.of(new Answer(1, 1), new Answer(2, 1)), null)
        );

        assertEquals(400, error.getStatus());
    }

    @Test
    void scoresOnServerAndCompletesSession() {
        var result = service.submit(10, List.of(new Answer(1, 1), new Answer(2, 2)), null);

        assertEquals(2, result.correctCount());
        assertEquals(2, result.total());
        verify(mapper).saveAnswer(10, 1, 1, true);
        verify(mapper).saveAnswer(10, 2, 2, true);
        verify(mapper).completeSession(10);
    }
}
