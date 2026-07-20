import { FormEvent, useMemo, useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { DataTable, Panel, Screen, StateMessage, StatusBadge } from '../../../components/ui';
import { QuoteCard } from '../components/QuoteCard';
import { parseRequirements, recommendBuild } from '../quoteApi';
import type { BuildSummary, ParsedRequirement, ToolResult } from '../types';

export function RequirementPage() {
  const [message, setMessage] = useState('');
  const [budget, setBudget] = useState('');
  const [usageTags, setUsageTags] = useState('');
  const [resolution, setResolution] = useState('');
  const [preferredVendors, setPreferredVendors] = useState('');
  const [priority, setPriority] = useState('');
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [selectedBuildId, setSelectedBuildId] = useState<string | null>(null);

  const parseMutation = useMutation({
    mutationFn: () => parseRequirements({
      message: message.trim(),
      budget: numberOrUndefined(budget),
      usageTags: splitText(usageTags),
      resolution: resolution.trim() || undefined,
      preferredVendors: splitText(preferredVendors),
      priority: priority.trim() || undefined
    }),
    onSuccess: () => {
      setAnswers({});
      setSelectedBuildId(null);
      recommendMutation.reset();
    }
  });

  const recommendMutation = useMutation({
    mutationFn: (requirement: ParsedRequirement) => recommendBuild(requirement.id, answers),
    onSuccess: (data) => {
      setSelectedBuildId(data.recommendations[0]?.id ?? null);
    }
  });

  const parsed = parseMutation.data;
  const recommendations = recommendMutation.data?.recommendations ?? [];
  const selectedBuild = useMemo(
    () => recommendations.find((build) => build.id === selectedBuildId) ?? recommendations[0],
    [recommendations, selectedBuildId]
  );
  const canParse = message.trim().length > 0 && !parseMutation.isPending;
  const canRecommend = Boolean(parsed) && !recommendMutation.isPending;

  function submitParse(event: FormEvent) {
    event.preventDefault();
    if (canParse) {
      parseMutation.mutate();
    }
  }

  function runRecommend() {
    if (parsed && canRecommend) {
      recommendMutation.mutate(parsed);
    }
  }

  return (
    <Screen>
      <div className="space-y-5">
        <div className="grid gap-5 xl:grid-cols-[minmax(0,520px)_minmax(0,1fr)_320px]">
          <Panel title="AI 견적 입력" subtitle="자연어 요구사항은 필수, 나머지는 추천 품질 보강용 선택 입력입니다.">
            <form onSubmit={submitParse} className="space-y-4">
              <textarea
                value={message}
                onChange={(event) => setMessage(event.target.value)}
                className="h-36 w-full rounded border border-slate-300 p-4 text-sm outline-none focus:border-brand-blue"
                placeholder="예: 200만원 안에서 QHD 게임과 개발을 같이 할 PC 추천해줘. NVIDIA 선호."
              />
              <div className="text-xs font-bold text-slate-400">상세 조건 (선택)</div>
              <div className="grid grid-cols-2 gap-3">
                <Input label="예산" value={budget} onChange={setBudget} placeholder="2,000,000" />
                <Input label="주 용도" value={usageTags} onChange={setUsageTags} placeholder="게임, 개발" />
                <Input label="해상도" value={resolution} onChange={setResolution} placeholder="QHD" />
                <Input label="브랜드 선호" value={preferredVendors} onChange={setPreferredVendors} placeholder="NVIDIA" />
                <div className="col-span-2">
                  <Input label="우선순위" value={priority} onChange={setPriority} placeholder="성능 > 안정성 > 가격" />
                </div>
              </div>
              <div className="flex gap-3">
                <button disabled={!canParse} className="rounded bg-brand-blue px-5 py-2 text-sm font-bold text-white disabled:cursor-not-allowed disabled:bg-slate-300">
                  {parseMutation.isPending ? '분석 중' : '요구사항 분석'}
                </button>
                <button type="button" onClick={() => { setMessage(''); setBudget(''); setUsageTags(''); setResolution(''); setPreferredVendors(''); setPriority(''); }} className="rounded px-3 py-2 text-sm font-bold text-slate-500 hover:bg-slate-100 hover:text-slate-700">
                  초기화
                </button>
              </div>
            </form>
          </Panel>

          <Panel title="AI 분석 상태" subtitle="추천 준비 진행 상황">
            {parseMutation.isIdle ? <StateMessage type="info" title="분석 전" body="요구사항을 입력하면 AI가 추천에 필요한 조건을 정리합니다." /> : null}
            {parseMutation.isPending ? <StateMessage type="info" title="요구사항 분석 중" body="입력 내용을 바탕으로 추천 준비와 추가 질문 생성을 진행하고 있습니다." /> : null}
            {parseMutation.isError ? <StateMessage type="warn" title="요구사항 분석 실패" body="자연어 입력이 비어 있거나 API 응답을 불러오지 못했습니다." /> : null}
            {parsed ? <StateMessage type="success" title="분석 완료" body="추천에 필요한 기본 조건을 정리했습니다. 필요한 추가 질문만 오른쪽에 표시했습니다." /> : null}
          </Panel>

          <Panel title="추가 질문" subtitle="모호한 항목만 선택적으로 답변">
            {!parsed ? <StateMessage type="info" title="분석 후 활성화" body="추가 질문은 AI 분석 후 필요한 항목만 표시됩니다." /> : null}
            {parsed ? (
              <div className="space-y-3">
                {parsed.questions.length === 0 ? <StateMessage type="success" title="추가 질문 없음" body="추천 생성에 필요한 기본 조건이 충족되었습니다." /> : null}
                {parsed.questions.map((question) => (
                  <label key={question.key} className="block rounded border border-slate-200 bg-white p-3 text-xs">
                    <span className="mb-2 block font-bold text-slate-700">{question.label}</span>
                    <select
                      value={answers[question.key] ?? ''}
                      onChange={(event) => setAnswers((current) => ({ ...current, [question.key]: event.target.value }))}
                      className="h-9 w-full rounded border border-slate-300 px-2"
                    >
                      <option value="">기본값 사용</option>
                      {question.options.map((option) => <option key={option} value={option}>{option}</option>)}
                    </select>
                  </label>
                ))}
                <button type="button" disabled={!canRecommend} onClick={runRecommend} className="w-full rounded bg-brand-blue px-4 py-3 text-sm font-bold text-white disabled:cursor-not-allowed disabled:bg-slate-300">
                  {recommendMutation.isPending ? '추천 생성 중' : '추천 결과 보기'}
                </button>
              </div>
            ) : null}
          </Panel>
        </div>

        {recommendMutation.isPending ? (
          <Panel title="추천 결과 생성 중" subtitle="내부 자산 조합과 자동 검증을 실행하고 있습니다.">
            <StateMessage type="info" title="견적 생성 중" body="내부 부품 데이터에서 후보를 선택하고 호환성, 전력, 규격, 성능, 가격 검증을 수행합니다." />
          </Panel>
        ) : null}

        {recommendMutation.isError ? (
          <Panel title="추천 결과 오류">
            <StateMessage type="warn" title="추천 생성 실패" body="내부 자산 또는 AI 실행 결과를 불러오지 못했습니다." />
          </Panel>
        ) : null}

        {recommendations.length > 0 ? (
          <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_420px]">
            <div className="space-y-5">
              <Panel title={`추천 조합 ${recommendations.length}개`} subtitle="내부 자산, 저장된 현재가, 검증 결과를 기반으로 생성">
                <div className="flex gap-4 overflow-x-auto pb-1">
                  {recommendations.map((build) => (
                    <QuoteCard key={build.id} build={build} selected={build.id === selectedBuild?.id} onSelect={(nextBuild) => setSelectedBuildId(nextBuild.id)} />
                  ))}
                </div>
              </Panel>
              <Panel title="검증 결과" subtitle={selectedBuild ? `${selectedBuild.name} 기준` : undefined}>
                <DataTable columns={['검증 항목', '상태', '신뢰도', '요약']} rows={toolRows(selectedBuild?.toolResults ?? [])} />
              </Panel>
            </div>
            <Panel title="AI 추천 근거">
              <div className="space-y-4">
                <StateMessage
                  type={selectedBuild?.agentSummary ? 'success' : 'info'}
                  title={selectedBuild?.agentSummary ? 'AI 요약' : 'AI 요약 준비 중'}
                  body={selectedBuild?.agentSummary ?? '추천 구성과 검증 결과는 생성되었습니다. AI 요약은 잠시 뒤에 표시될 수 있습니다.'}
                />
                <DataTable columns={['항목', '값']} rows={[
                  { 항목: '근거 자료', 값: selectedBuild?.evidenceIds?.length ? `${selectedBuild.evidenceIds.length}개` : '—' },
                  { 항목: '경고', 값: selectedBuild?.warnings?.length ? `${selectedBuild.warnings.length}개` : '없음' }
                ]} />
              </div>
            </Panel>
          </div>
        ) : null}
      </div>
    </Screen>
  );
}

function Input({ label, value, onChange, placeholder }: { label: string; value: string; onChange: (value: string) => void; placeholder?: string }) {
  return (
    <label className="block text-xs font-bold text-slate-600">
      <span className="mb-1 block">{label}</span>
      <input value={value} onChange={(event) => onChange(event.target.value)} placeholder={placeholder} className="h-10 w-full rounded border border-slate-300 px-3 text-sm font-normal text-slate-900 outline-none focus:border-brand-blue" />
    </label>
  );
}

function toolRows(results: ToolResult[]) {
  return results.map((row) => ({
    '검증 항목': toolLabel(row.tool),
    상태: <StatusBadge status={row.status} />,
    신뢰도: <StatusBadge status={row.confidence} />,
    요약: row.summary
  }));
}

function toolLabel(tool: string) {
  switch (tool) {
    case 'compatibility':
      return '호환성 검증';
    case 'power':
      return '전력 검증';
    case 'size':
      return '규격 검증';
    case 'performance':
      return '성능 범위';
    case 'price':
      return '가격 확인';
    default:
      return tool;
  }
}

function splitText(value: string) {
  return value.split(',').map((item) => item.trim()).filter(Boolean);
}

// '200만' / '200만원' / '1억' 같은 한국식 표기를 원 단위 숫자로 해석한다.
// parseInt만 쓰면 '200만'이 200(원)으로 조용히 오해석되어 서버에 전송된다.
function numberOrUndefined(value: string) {
  const normalized = value.replace(/,/g, '').replace(/원/g, '').trim();
  if (!normalized) {
    return undefined;
  }
  const unitMatch = normalized.match(/^(\d+(?:\.\d+)?)\s*(억|만)$/);
  if (unitMatch) {
    const amount = Number.parseFloat(unitMatch[1]);
    const multiplier = unitMatch[2] === '억' ? 100_000_000 : 10_000;
    return Number.isFinite(amount) ? Math.round(amount * multiplier) : undefined;
  }
  const parsed = Number.parseInt(normalized, 10);
  return Number.isFinite(parsed) ? parsed : undefined;
}
