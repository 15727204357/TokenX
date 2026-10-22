CREATE TABLE seckill_request (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    request_id BINARY(16) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    activity_id BIGINT UNSIGNED NOT NULL,
    status TINYINT NOT NULL COMMENT '0=QUEUED, 1=PROCESSING, 2=ORDERED, 3=REJECTED, 4=FAILED',
    order_id BIGINT UNSIGNED NULL,
    error_code VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_request (user_id, request_id),
    UNIQUE KEY uk_request_id (request_id),
    KEY idx_activity_user_status (activity_id, user_id, status),
    KEY idx_request_status_updated (status, updated_at),
    CONSTRAINT fk_seckill_request_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_seckill_request_activity FOREIGN KEY (activity_id) REFERENCES seckill_activity (id),
    CONSTRAINT fk_seckill_request_order FOREIGN KEY (order_id) REFERENCES purchase_order (id),
    CONSTRAINT chk_seckill_request_status CHECK (status IN (0, 1, 2, 3, 4)),
    CONSTRAINT chk_seckill_request_result CHECK (
        (status = 2 AND order_id IS NOT NULL AND error_code IS NULL)
        OR (status IN (0, 1) AND order_id IS NULL AND error_code IS NULL)
        OR (status IN (3, 4) AND order_id IS NULL AND error_code IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
