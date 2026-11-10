INSERT INTO level (id, module_id, level_no, title, is_free)
VALUES (4, 1, 4, '成语故事', TRUE);

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
        10, 1, '目瞪口呆', '目瞪□呆', 'mù dèng ＿ dāi',
        'mù dèng kǒu dāi', '口', 'kǒu', '吃惊得瞪大眼睛，张着嘴说不出话。', 2
    ),
    (
        11, 1, '画蛇添足', '画蛇□足', 'huà shé ＿ zú',
        'huà shé tiān zú', '添', 'tiān', '做了多余的事，反而把事情弄坏了。', 2
    ),
    (
        12, 1, '大吃一惊', '大吃一□', 'dà chī yī ＿',
        'dà chī yī jīng', '惊', 'jīng', '遇到意想不到的事，感到非常吃惊。', 2
    );

INSERT INTO level_question (level_id, question_id, sort_order)
VALUES
    (4, 10, 1),
    (4, 11, 2),
    (4, 12, 3);
