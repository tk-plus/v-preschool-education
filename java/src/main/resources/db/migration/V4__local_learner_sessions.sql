ALTER TABLE answer_session
    ADD COLUMN local_learner_id VARCHAR(36) NULL COMMENT '本地试玩者标识';

CREATE INDEX idx_answer_session_learner ON answer_session (local_learner_id, status);
