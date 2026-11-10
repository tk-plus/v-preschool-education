INSERT INTO level (id, module_id, level_no, title, is_free)
VALUES
    (2, 1, 2, '认识成语', TRUE),
    (3, 1, 3, '初显身手', TRUE);

INSERT INTO question (
    id,
    module_id,
    source_text,
    prompt_text,
    prompt_pinyin,
    full_pinyin,
    answer_text,
    answer_pinyin,
    meaning,
    difficulty
)
VALUES
    (
        4, 1, '一目了然', '一目□然', 'yī mù ＿ rán',
        'yī mù liǎo rán', '了', 'liǎo', '一眼就能看清楚。', 1
    ),
    (
        5, 1, '画龙点睛', '画龙□睛', 'huà lóng ＿ jīng',
        'huà lóng diǎn jīng', '点', 'diǎn', '在关键地方加上一笔，让作品更精彩。', 1
    ),
    (
        6, 1, '井井有条', '井井有□', 'jǐng jǐng yǒu ＿',
        'jǐng jǐng yǒu tiáo', '条', 'tiáo', '做事很有次序。', 1
    ),
    (
        7, 1, '守株待兔', '守□待兔', 'shǒu ＿ dài tù',
        'shǒu zhū dài tù', '株', 'zhū', '只等好运而不努力。', 2
    ),
    (
        8, 1, '亡羊补牢', '亡羊补□', 'wáng yáng bǔ ＿',
        'wáng yáng bǔ láo', '牢', 'láo', '出了问题及时补救。', 2
    ),
    (
        9, 1, '争先恐后', '争先□后', 'zhēng xiān ＿ hòu',
        'zhēng xiān kǒng hòu', '恐', 'kǒng', '争着向前，怕落在后面。', 2
    );

INSERT INTO level_question (level_id, question_id, sort_order)
VALUES
    (2, 4, 1),
    (2, 5, 2),
    (2, 6, 3),
    (3, 7, 1),
    (3, 8, 2),
    (3, 9, 3);
