export const agentStateRows = [
  { step: '1', state: 'INPUT_RECEIVED', owner: 'Frontend', api: 'POST /api/requirements/parse', output: 'Requirement' },
  { step: '2', state: 'RAG_SEARCHED', owner: 'RAG Service', api: 'GET /api/rag/search', output: 'RagEvidence[]' },
  { step: '3', state: 'TOOLS_CALLED', owner: 'Agent Orchestrator', api: 'POST /api/ai/agent-sessions/:id/run', output: 'ToolInvocation[]' },
  { step: '4', state: 'SUMMARY_READY', owner: 'Agent + LLM', api: 'GET /api/ai/agent-sessions/:id', output: 'Build explanation' },
  { step: '5', state: 'FALLBACK_READY', owner: 'Backend', api: 'same session', output: 'Seed result when LLM fails' }
];

export const toolInvocationRows = [
  { id: '00000000-0000-4000-8000-000000005001', tool: 'compatibility', status: 'PASS', confidence: 'HIGH', latency: '120ms', summary: 'CPU와 메인보드 소켓 호환' },
  { id: '00000000-0000-4000-8000-000000005002', tool: 'power', status: 'WARN', confidence: 'MEDIUM', latency: '168ms', summary: '피크 전력 기준 PSU 여유율 낮음' },
  { id: '00000000-0000-4000-8000-000000005003', tool: 'performance', status: 'PASS', confidence: 'MEDIUM', latency: '210ms', summary: 'QHD 게임 기준 GPU 우선 구성 적합' },
  { id: '00000000-0000-4000-8000-000000005004', tool: 'price', status: 'PASS', confidence: 'LOW', latency: '340ms', summary: '최근 스냅샷 기준 예산 내 구성' }
];

export const ragEvidenceRows = [
  { id: '00000000-0000-4000-8000-000000004001', sourceId: 'psu-rule-001', summary: 'GPU 피크 전력과 CPU TDP 합산 후 여유율 적용', score: '0.91', owner: '3번 Agent/RAG' },
  { id: '00000000-0000-4000-8000-000000004002', sourceId: 'qhd-gaming-4070s', summary: 'QHD 게임 기준 GPU 우선 구성 근거', score: '0.84', owner: '3번 Agent/RAG' },
  { id: '00000000-0000-4000-8000-000000004003', sourceId: 'as-thermal-001', summary: 'GPU 온도 상승과 프레임 드랍 간 단순 상관 규칙', score: '0.78', owner: '4번 Log/AS' }
];

export const adminTicketDetailRows = [
  { field: 'ticketId', value: '00000000-0000-4000-8000-000000006001' },
  { field: 'user', value: 'user@example.com' },
  { field: 'symptom', value: '게임 중 프레임 급락' },
  { field: 'logRange', value: '최근 30분' },
  { field: 'consent', value: '로그 업로드 명시 동의 필요' },
  { field: 'retention', value: '업로드 로그 30일 보관 후 삭제 예정' },
  { field: 'causeCandidate1', value: 'GPU 온도 과열 가능성' },
  { field: 'causeCandidate2', value: '드라이버 오류 이벤트 반복 가능성' },
  { field: 'upgradeCandidate', value: '케이스 쿨링 또는 GPU 상위 모델 후보 표시' }
];
