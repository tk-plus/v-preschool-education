CREATE TABLE learning_module (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '模块ID',
    code VARCHAR(32) NOT NULL COMMENT '模块编码，例如 idiom',
    name VARCHAR(64) NOT NULL COMMENT '模块名称',
    status VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED' COMMENT '模块状态',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uq_learning_module_code (code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '学习模块表';

CREATE TABLE level (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '关卡ID',
    module_id BIGINT NOT NULL COMMENT '所属模块ID',
    level_no INT NOT NULL COMMENT '模块内关卡序号',
    title VARCHAR(64) NOT NULL COMMENT '关卡标题',
    is_free BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否免费体验关卡',
    status VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED' COMMENT '关卡状态',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uq_level_module_no (module_id, level_no),
    CONSTRAINT fk_level_module
        FOREIGN KEY (module_id) REFERENCES learning_module (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '学习关卡表';

CREATE TABLE question (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '题目ID',
    module_id BIGINT NOT NULL COMMENT '所属模块ID',
    source_text VARCHAR(255) NOT NULL COMMENT '完整成语或原始文本',
    prompt_text VARCHAR(255) NOT NULL COMMENT '答题前展示的缺字文本',
    prompt_pinyin VARCHAR(255) NOT NULL COMMENT '答题前拼音，缺字位置留空',
    full_pinyin VARCHAR(255) NOT NULL COMMENT '完整文本的拼音',
    answer_text VARCHAR(64) NOT NULL COMMENT '缺字的正确答案',
    answer_pinyin VARCHAR(64) NOT NULL COMMENT '正确答案的拼音',
    meaning VARCHAR(255) NOT NULL COMMENT '适合孩子理解的释义',
    difficulty TINYINT NOT NULL COMMENT '题目难度级别',
    status VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED' COMMENT '题目状态',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_question_module_status (module_id, status),
    CONSTRAINT fk_question_module
        FOREIGN KEY (module_id) REFERENCES learning_module (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '题库表';

CREATE TABLE level_question (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '关卡题目关联ID',
    level_id BIGINT NOT NULL COMMENT '关卡ID',
    question_id BIGINT NOT NULL COMMENT '题目ID',
    sort_order TINYINT NOT NULL COMMENT '题目在关卡中的顺序',
    PRIMARY KEY (id),
    UNIQUE KEY uq_level_question (level_id, question_id),
    UNIQUE KEY uq_level_order (level_id, sort_order),
    KEY idx_level_question_question (question_id),
    CONSTRAINT fk_lq_level
        FOREIGN KEY (level_id) REFERENCES level (id),
    CONSTRAINT fk_lq_question
        FOREIGN KEY (question_id) REFERENCES question (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '关卡与题目关联表';

CREATE TABLE answer_session (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '答题回合ID',
    level_id BIGINT NOT NULL COMMENT '所属关卡ID',
    status VARCHAR(16) NOT NULL DEFAULT 'IN_PROGRESS' COMMENT '答题状态',
    started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '开始时间',
    submitted_at DATETIME(3) NULL COMMENT '提交时间',
    PRIMARY KEY (id),
    KEY idx_answer_session_level (level_id),
    CONSTRAINT fk_session_level
        FOREIGN KEY (level_id) REFERENCES level (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '答题回合表';

CREATE TABLE session_question (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '回合题目ID',
    session_id BIGINT NOT NULL COMMENT '答题回合ID',
    question_id BIGINT NOT NULL COMMENT '题目ID',
    sort_order TINYINT NOT NULL COMMENT '题目在回合中的顺序',
    selected_tile_no TINYINT NULL COMMENT '最终选择的字块序号',
    is_correct BOOLEAN NULL COMMENT '是否答对，未提交时为空',
    PRIMARY KEY (id),
    UNIQUE KEY uq_session_question (session_id, question_id),
    KEY idx_session_question_question (question_id),
    CONSTRAINT fk_sq_session
        FOREIGN KEY (session_id) REFERENCES answer_session (id),
    CONSTRAINT fk_sq_question
        FOREIGN KEY (question_id) REFERENCES question (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '答题回合题目及作答表';

CREATE TABLE session_tile (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '回合字块ID',
    session_id BIGINT NOT NULL COMMENT '答题回合ID',
    tile_no TINYINT NOT NULL COMMENT '字块在本回合中的序号',
    character_text VARCHAR(4) NOT NULL COMMENT '字块显示的汉字',
    pinyin VARCHAR(32) NOT NULL COMMENT '字块汉字的拼音',
    PRIMARY KEY (id),
    UNIQUE KEY uq_session_tile_no (session_id, tile_no),
    CONSTRAINT fk_tile_session
        FOREIGN KEY (session_id) REFERENCES answer_session (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = '答题回合共用字块表';
