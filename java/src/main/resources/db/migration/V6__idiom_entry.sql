CREATE TABLE idiom_entry (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '成语词条ID',
    term VARCHAR(64) NOT NULL COMMENT '成语原文',
    source_pinyin VARCHAR(255) NULL COMMENT '原始数据中的拼音，需人工核对',
    source_name VARCHAR(64) NOT NULL COMMENT '词条来源标识',
    is_four_character BOOLEAN NOT NULL COMMENT '是否由四个汉字组成',
    pinyin_format_ok BOOLEAN NOT NULL COMMENT '是否通过四字题所需的拼音格式检查',
    child_meaning VARCHAR(255) NULL COMMENT '审核后填写的儿童版释义',
    difficulty TINYINT NULL COMMENT '审核后填写的难度级别',
    review_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '词条审核状态',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '入库时间',
    PRIMARY KEY (id),
    UNIQUE KEY uq_idiom_entry_term (term)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_bin
  COMMENT = '成语候选词库，与已发布填字题分开维护';
