-- 상담 메시지 조회 가속: messages()의
--   WHERE room_id = ? ORDER BY created_at DESC, id DESC LIMIT 100
-- 에 정확히 맞는 복합 인덱스. 열린 위젯/관리자 상세가 방마다 최근 100건을
-- 별도 정렬 없이 인덱스 순서로 바로 읽게 한다(닫힌 위젯은 요약 경로라 미해당).
CREATE INDEX IF NOT EXISTS ix_support_chat_messages_room_created
    ON support_chat_messages (room_id, created_at DESC, id DESC);
