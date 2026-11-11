ALTER TABLE idiom_entry
    ADD COLUMN reviewed_pinyin VARCHAR(255) NULL COMMENT '管理端审核确认的拼音';
