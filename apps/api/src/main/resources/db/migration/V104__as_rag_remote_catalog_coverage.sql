UPDATE as_rag_evidence
SET metadata = '{"keywords":["display driver reset","display driver","gpu driver","graphics driver","nvlddmkm","device manager","windows update","driver install failure","driver rollback","rollback","블루스크린","드라이버 오류"],"remoteActions":["DRIVER_ROLLBACK","WINDOWS_UPDATE_CHECK","DEVICE_RESET"],"visitReasons":[]}'::jsonb
WHERE source_id = 'as-rag-remote-driver-os';

INSERT INTO as_rag_evidence (
  source_id,
  symptom_type,
  source_type,
  recommended_service,
  support_decision,
  reason_code,
  title,
  chunk_text,
  summary,
  score,
  metadata
) VALUES
(
  'as-rag-remote-agent',
  'REMOTE_AGENT',
  'TROUBLESHOOTING',
  'REMOTE_SUPPORT',
  'REMOTE_POSSIBLE',
  'AGENT_INSTALL_OR_UPLOAD_FAILURE',
  'Agent install, registration, upload, or permission errors are remote-support first cases',
  'Agent install failure, registration failure, upload error, permission error, config parse failure, service status issue, tray status issue, last heartbeat problem, last upload error, or token status issue usually fit a remote support workflow when the OS and web access are available.',
  'Agent 설치, 등록, 업로드, 권한, config 오류는 OS와 웹 접속이 가능하면 원격지원으로 먼저 확인할 가능성이 높습니다.',
  0.90,
  '{"keywords":["agent registration failed","registration failed","agent register failed","upload error","last upload error","agent upload failure","permission error","agent permission denied","config parse failed","config schema","service status","tray status","token status","agent install","agent restart","agent config","heartbeat problem"],"remoteActions":["CHECK_AGENT_CONFIG"],"visitReasons":[]}'::jsonb
),
(
  'as-rag-remote-startup-service',
  'REMOTE_STARTUP_SERVICE',
  'TROUBLESHOOTING',
  'REMOTE_SUPPORT',
  'REMOTE_POSSIBLE',
  'BACKGROUND_SERVICE_PRESSURE',
  'Startup app or background service load is a remote-support first case',
  'Startup app spike, startup impact, background service pressure, service crash loop, service restart count, idle high CPU, or repeated background task resource pressure can usually be inspected remotely before visit scheduling.',
  '시작프로그램 또는 백그라운드 서비스 부하는 원격지원으로 먼저 확인할 가능성이 높습니다.',
  0.86,
  '{"keywords":["startup app","startup impact","startup spike","background service","service crash loop","service restart","service restart count","idle high cpu","background task","background service pressure","시작프로그램","백그라운드 서비스"],"remoteActions":["CHECK_STARTUP_APPS"],"visitReasons":[]}'::jsonb
)
ON CONFLICT (source_id) DO UPDATE
SET symptom_type = EXCLUDED.symptom_type,
    source_type = EXCLUDED.source_type,
    recommended_service = EXCLUDED.recommended_service,
    support_decision = EXCLUDED.support_decision,
    reason_code = EXCLUDED.reason_code,
    title = EXCLUDED.title,
    chunk_text = EXCLUDED.chunk_text,
    summary = EXCLUDED.summary,
    score = EXCLUDED.score,
    metadata = EXCLUDED.metadata,
    active = true;
