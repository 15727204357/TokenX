ALTER TABLE seckill_request
    DROP CHECK chk_seckill_request_status,
    DROP CHECK chk_seckill_request_result;

ALTER TABLE seckill_request
    MODIFY status TINYINT NOT NULL
        COMMENT '0=QUEUED, 1=PROCESSING, 2=ORDERED, 3=REJECTED, 4=FAILED, 5=UNKNOWN, 6=RESERVED',
    ADD CONSTRAINT chk_seckill_request_status CHECK (status IN (0, 1, 2, 3, 4, 5, 6)),
    ADD CONSTRAINT chk_seckill_request_result CHECK (
        (status = 2 AND order_id IS NOT NULL AND error_code IS NULL)
        OR (status IN (0, 1, 5, 6) AND order_id IS NULL AND error_code IS NULL)
        OR (status IN (3, 4) AND order_id IS NULL AND error_code IS NOT NULL)
    );
