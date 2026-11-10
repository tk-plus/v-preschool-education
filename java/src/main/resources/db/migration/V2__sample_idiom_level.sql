INSERT INTO learning_module (id, code, name)
VALUES (1, 'idiom', '成语填字');

INSERT INTO level (id, module_id, level_no, title, is_free)
VALUES (1, 1, 1, '初识成语', TRUE);

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
        1, 1, '兴高采烈', '兴高□烈', 'xìng gāo ＿ liè',
        'xìng gāo cǎi liè', '采', 'cǎi', '形容非常高兴。', 1
    ),
    (
        2, 1, '一心一意', '一心一□', 'yī xīn yī ＿',
        'yī xīn yī yì', '意', 'yì', '做事专心，没有别的念头。', 1
    ),
    (
        3, 1, '五颜六色', '五颜六□', 'wǔ yán liù ＿',
        'wǔ yán liù sè', '色', 'sè', '形容颜色很多、很好看。', 1
    );

INSERT INTO level_question (level_id, question_id, sort_order)
VALUES
    (1, 1, 1),
    (1, 2, 2),
    (1, 3, 3);
