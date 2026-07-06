UPDATE as_rag_evidence
SET support_decision = 'VISIT_REQUIRED',
    title = 'SMART critical and repeated disk I/O errors require visit inspection',
    chunk_text = 'SMART critical, predictive failure, bad block, repeated disk I/O error, filesystem error, disappearing drive, or storage device instability are hardware-risk signals. These should not be treated as a simple remote cleanup case. Visit support should be reviewed; replacement possibility is expressed through visit reasons.',
    summary = '디스크 장애 위험 신호는 방문 점검으로 올리고, 교체 가능성은 방문 사유로만 표현합니다.',
    metadata = '{"keywords":["smart critical","predictive failure","bad block","disk i/o","i/o error","filesystem error","drive disappeared","디스크 오류","배드블록","smart","저장장치 장애"],"remoteActions":[],"visitReasons":["STORAGE_REPLACEMENT_SUSPECTED"]}'::jsonb
WHERE source_id = 'as-rag-visit-disk-failure';

UPDATE as_rag_evidence
SET title = 'Repeated power loss or Kernel-Power events require visit inspection',
    chunk_text = 'Kernel-Power 41, unexpected shutdown, sudden shutdown, or power loss under load indicate possible PSU or power path risk when repeated. Visit support is recommended for administrator review.',
    summary = '전원 꺼짐 또는 Kernel-Power 반복 신호는 방문 점검이 필요할 수 있습니다.',
    metadata = '{"keywords":["kernel-power","kernel power","event id 41","unexpected shutdown","sudden shutdown","power loss","power loss under load","psu","전원 꺼짐"],"remoteActions":[],"visitReasons":["PSU_OR_POWER_PATH_RISK"]}'::jsonb
WHERE source_id = 'as-rag-visit-power-thermal';

UPDATE as_rag_evidence
SET support_decision = 'NEEDS_MORE_INFO',
    summary = '지원 범위 밖 가능성이 있어 바로 원격/방문 신청보다 진단 또는 관리자 확인이 필요합니다.',
    metadata = '{"keywords":["fps tuning","overclock","isp","router","printer","peripheral","data recovery","illegal software","physical damage","오버클럭","공유기","프린터","주변기기","데이터 복구","물리 파손"],"remoteActions":[],"visitReasons":[],"blockingFactors":["UNSUPPORTED_SCOPE"]}'::jsonb
WHERE source_id = 'as-rag-unsupported-scope';

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
  'as-rag-visit-boot-remote-blocked',
  'VISIT_BOOT_REMOTE_BLOCKED',
  'TROUBLESHOOTING',
  'VISIT_SUPPORT',
  'VISIT_REQUIRED',
  'BOOT_OR_REMOTE_BLOCKED',
  'Boot failure or remote connection blocked requires visit inspection',
  'Boot failure, cannot boot, device offline, long heartbeat gap, repeated Quick Assist failure, or remote connection unavailable means the support team cannot safely diagnose through a remote session. Visit inspection should be reviewed by an administrator.',
  '부팅 불가, Agent 오프라인, 원격 연결 불가처럼 원격 확인 자체가 막힌 경우 방문 점검이 필요할 수 있습니다.',
  0.93,
  '{"keywords":["boot failure","cannot boot","no boot","os boot failure","device offline","heartbeat missing","heartbeat gap","remote unavailable","remote connection unavailable","quick assist unavailable","quick assist failed","부팅 불가","원격 연결 불가"],"remoteActions":[],"visitReasons":["DEVICE_OFFLINE","REMOTE_HELP_NOT_AVAILABLE"]}'::jsonb
),
(
  'as-rag-visit-fan-thermal',
  'VISIT_FAN_THERMAL',
  'TROUBLESHOOTING',
  'VISIT_SUPPORT',
  'VISIT_REQUIRED',
  'THERMAL_SHUTDOWN_SIGNAL',
  'Fan failure or thermal shutdown requires visit inspection',
  'Thermal shutdown, fan rpm 0, fan not spinning, cooling fan stopped, or repeated thermal throttle indicates cooling hardware risk. Visit support is recommended for administrator review.',
  '팬 미동작 또는 thermal shutdown 신호는 방문 점검이 필요할 수 있습니다.',
  0.94,
  '{"keywords":["thermal shutdown","fan rpm 0","fan not spinning","cooling fan stopped","thermal throttle","overheat","fan failure","팬 미동작","과열"],"remoteActions":[],"visitReasons":["THERMAL_SERVICE_REQUIRED"]}'::jsonb
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
