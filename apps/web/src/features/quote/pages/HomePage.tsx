import { type CSSProperties, type FormEvent, type PointerEvent as ReactPointerEvent, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  Activity,
  ArrowRight,
  Bot,
  CheckCircle2,
  Cpu,
  Database,
  FileText,
  GitBranch,
  HardDrive,
  Heart,
  LifeBuoy,
  Monitor,
  Move,
  PackageCheck,
  SearchCheck,
  Send,
  ShoppingCart,
  Sparkles,
  Star,
  Zap,
  type LucideIcon
} from 'lucide-react';
import { Screen, StatusBadge } from '../../../components/ui';
import type { BuildItem, BuildSummary, ToolResult } from '../types';
import { builds } from '../mocks/quoteMock';

const STORAGE_KEY = 'buildgraph.home.consultation';
const ASSISTANT_BAR_ESTIMATED_HEIGHT = 260;

type RecommendationMode = 'balanced' | 'gaming' | 'dev' | 'ai' | 'budget' | 'quiet' | 'creative' | 'office';
type WizardStep = 'usage' | 'resolution';
type WizardAnswerKey = 'usage' | 'resolution';

type ChatMessage = {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  createdAt: string;
};

type AssistantPosition = {
  x: number;
  y: number;
};

type ConsultationState = {
  prompt: string;
  mode: RecommendationMode;
  headline: string;
  wizardStep: WizardStep;
  answers: Partial<Record<WizardAnswerKey, string>>;
  recommendations: BuildSummary[];
  messages: ChatMessage[];
  assistantPosition: AssistantPosition;
};

type WizardOption = {
  label: string;
  answerKey: WizardAnswerKey;
  mode: RecommendationMode;
  nextStep: WizardStep;
  assistant: string;
};

const starterPrompts = [
  { label: 'QHD 게임', value: '200만원 안에서 QHD 게임과 개발을 같이 할 PC 추천해줘. NVIDIA 선호.' },
  { label: 'AI CUDA 실습', value: '300만원 안에서 CUDA 실습과 개발을 같이 할 수 있는 AI 학습용 PC 추천해줘.' },
  { label: '저소음 작업', value: '소음이 적고 안정적인 개발 작업용 PC를 220만원 안에서 맞춰줘.' },
  { label: '150만원 가성비', value: '150만원 안에서 FHD 게임과 문서 작업을 할 가성비 PC 추천해줘.' }
];

const INITIAL_ASSISTANT_REPLY = '추천 컴퓨터를 메인화면에 제공해드렸습니다. 혹시 어떤 용도로 사용하시나요?';

const usageWizardOptions: WizardOption[] = [
  { label: '게임', answerKey: 'usage', mode: 'gaming', nextStep: 'resolution', assistant: '게임용 기준으로 추천을 조정했습니다. 주로 어떤 해상도로 플레이하시나요?' },
  { label: '개발', answerKey: 'usage', mode: 'dev', nextStep: 'usage', assistant: '개발 작업 기준으로 추천 컴퓨터를 다시 정리했습니다. IDE, Docker, 빌드 작업을 고려했습니다.' },
  { label: 'AI/CUDA', answerKey: 'usage', mode: 'ai', nextStep: 'usage', assistant: 'AI/CUDA 실습 기준으로 추천 컴퓨터를 다시 정리했습니다. GPU와 VRAM 여유를 우선했습니다.' },
  { label: '영상편집', answerKey: 'usage', mode: 'creative', nextStep: 'usage', assistant: '영상편집 기준으로 추천 컴퓨터를 다시 정리했습니다. CPU, RAM, SSD 작업 공간을 우선했습니다.' },
  { label: '사무/학습', answerKey: 'usage', mode: 'office', nextStep: 'usage', assistant: '사무/학습 기준으로 추천 컴퓨터를 다시 정리했습니다. 안정성과 예산 효율을 우선했습니다.' },
  { label: '저소음', answerKey: 'usage', mode: 'quiet', nextStep: 'usage', assistant: '저소음 기준으로 추천 컴퓨터를 다시 정리했습니다. 쿨링 소음과 전력 여유를 우선했습니다.' }
];

const resolutionWizardOptions: WizardOption[] = [
  { label: 'FHD', answerKey: 'resolution', mode: 'gaming', nextStep: 'usage', assistant: 'FHD 기준으로 추천 컴퓨터를 다시 정리했습니다. 비용 효율과 프레임 안정성을 우선했습니다.' },
  { label: 'QHD', answerKey: 'resolution', mode: 'gaming', nextStep: 'usage', assistant: 'QHD 기준으로 추천 컴퓨터를 다시 정리했습니다. GPU 성능과 전력 여유를 함께 반영했습니다.' },
  { label: '4K', answerKey: 'resolution', mode: 'gaming', nextStep: 'usage', assistant: '4K 기준으로 추천 컴퓨터를 다시 정리했습니다. GPU와 VRAM 여유를 더 높게 보았습니다.' },
  { label: '고주사율', answerKey: 'resolution', mode: 'gaming', nextStep: 'usage', assistant: '고주사율 기준으로 추천 컴퓨터를 다시 정리했습니다. GPU와 CPU 병목 가능성을 함께 반영했습니다.' }
];

const quickCategories: Array<{ label: string; detail: string; to: string; icon: LucideIcon }> = [
  { label: 'CPU', detail: '작업 성능 기준', to: '/self-quote?category=CPU', icon: Cpu },
  { label: 'GPU', detail: 'QHD/AI 실습 기준', to: '/self-quote?category=GPU', icon: Monitor },
  { label: 'RAM', detail: '개발/멀티태스킹', to: '/self-quote?category=RAM', icon: Database },
  { label: 'SSD', detail: '프로젝트 저장공간', to: '/self-quote?category=STORAGE', icon: HardDrive },
  { label: '파워', detail: '피크 전력 여유율', to: '/self-quote?category=PSU', icon: Zap },
  { label: '쿨러', detail: '발열/소음 여유', to: '/self-quote?category=COOLER', icon: Activity }
];

const featuredBuilds: Array<{ name: string; tag: string; price: number; originalPrice: number; spec: string; tone: string; to: string }> = [
  {
    name: 'QHD 게이밍 추천팩',
    tag: 'SALE 12%',
    price: 1980000,
    originalPrice: 2250000,
    spec: 'RTX 5070 · Ryzen 7 · DDR5 32GB',
    tone: 'from-blue-50 to-white',
    to: '/builds/00000000-0000-4000-8000-000000002001'
  },
  {
    name: 'AI CUDA 실습팩',
    tag: 'AI 추천',
    price: 2480000,
    originalPrice: 2690000,
    spec: 'VRAM 우선 · 850W PSU · 2TB SSD',
    tone: 'from-indigo-50 to-white',
    to: '/requirements/new'
  },
  {
    name: '저소음 작업팩',
    tag: '검증 통과',
    price: 2140000,
    originalPrice: 2290000,
    spec: '공랭 듀얼타워 · 흡기형 케이스',
    tone: 'from-emerald-50 to-white',
    to: '/builds/00000000-0000-4000-8000-000000002001'
  }
];

const popularPartDeals: Array<{ rank: number; label: string; category: string; price: number; sale: string; detail: string; to: string; icon: LucideIcon }> = [
  { rank: 1, label: 'RTX 5070 QHD 그래픽카드', category: 'GPU', price: 890000, sale: 'SALE', detail: 'QHD 고주사율 후보', to: '/self-quote?category=GPU', icon: Monitor },
  { rank: 2, label: 'Ryzen 7 작업용 CPU', category: 'CPU', price: 420000, sale: 'BEST', detail: '게임/개발 균형형', to: '/self-quote?category=CPU', icon: Cpu },
  { rank: 3, label: 'DDR5 32GB 메모리', category: 'RAM', price: 128000, sale: 'LOW', detail: '멀티태스킹 표준', to: '/self-quote?category=RAM', icon: Database },
  { rank: 4, label: 'ATX 3.1 850W 파워', category: 'PSU', price: 165000, sale: 'PASS', detail: '전력 여유 확보', to: '/self-quote?category=PSU', icon: Zap }
];

const verificationStages: Array<{ title: string; detail: string; meta: string; icon: LucideIcon }> = [
  { title: '요구사항 파싱', detail: '예산, 용도, 선호 브랜드를 구조화합니다.', meta: '프론트 시뮬레이션', icon: FileText },
  { title: 'RAG 근거', detail: '내부 자산, 가격, AS 기준을 화면에서 연결합니다.', meta: 'Evidence', icon: Database },
  { title: 'Tool 검증', detail: '호환성, 전력, 규격, 성능 범위 상태를 표시합니다.', meta: 'PASS/WARN/FAIL', icon: SearchCheck },
  { title: '추천 Build', detail: '질문에 맞춰 카드 순서와 경고 문구를 바꿉니다.', meta: 'Local state', icon: GitBranch }
];

const profileCopy: Record<RecommendationMode, { title: string; badge: string; summary: string; assistant: string }> = {
  balanced: {
    title: '균형형 추천을 조정했습니다',
    badge: '균형형',
    summary: '예산, 성능, 업그레이드 여유를 고르게 본 기본 추천입니다.',
    assistant: '예산과 용도를 균형 있게 맞춰 3가지 조합으로 정리했습니다.'
  },
  gaming: {
    title: 'QHD 게임 추천을 조정했습니다',
    badge: 'QHD 게임',
    summary: 'GPU 성능과 전력 여유를 우선해서 QHD 게임 기준으로 정렬했습니다.',
    assistant: 'QHD 게임 성능을 우선해서 GPU 중심 조합으로 다시 정렬했습니다.'
  },
  dev: {
    title: '개발 작업 추천을 조정했습니다',
    badge: '개발 작업',
    summary: '멀티태스킹, 메모리 용량, 저장공간 안정성을 우선했습니다.',
    assistant: '개발 IDE, 컨테이너, 빌드 작업을 고려해 메모리와 저장공간을 강화했습니다.'
  },
  ai: {
    title: 'AI 실습 추천을 조정했습니다',
    badge: 'AI CUDA',
    summary: 'CUDA 활용 가능성과 VRAM 여유를 우선해서 추천했습니다.',
    assistant: 'CUDA 실습과 모델 테스트를 고려해 GPU와 VRAM 우선 조합으로 조정했습니다.'
  },
  budget: {
    title: '가성비 추천을 조정했습니다',
    badge: '가성비',
    summary: '예산 제한 안에서 체감 성능이 큰 부품에 비용을 집중했습니다.',
    assistant: '예산을 낮게 잡고도 체감 성능이 남도록 CPU/GPU 균형을 조정했습니다.'
  },
  quiet: {
    title: '저소음 추천을 조정했습니다',
    badge: '저소음',
    summary: '쿨링 소음, 전력 여유, 케이스 흡기 구조를 우선했습니다.',
    assistant: '쿨링 소음과 전력 여유를 우선해서 다시 정렬했습니다.'
  },
  creative: {
    title: '영상편집 추천을 조정했습니다',
    badge: '영상편집',
    summary: 'CPU 멀티코어, RAM 용량, SSD 작업 공간을 우선했습니다.',
    assistant: '영상편집 작업을 고려해 CPU, RAM, SSD 작업 공간 중심으로 정렬했습니다.'
  },
  office: {
    title: '사무/학습 추천을 조정했습니다',
    badge: '사무/학습',
    summary: '문서 작업, 온라인 학습, 안정성과 예산 효율을 우선했습니다.',
    assistant: '사무/학습 용도를 고려해 안정성과 비용 효율 중심으로 정렬했습니다.'
  }
};

const recommendationTemplates: Record<RecommendationMode, Array<Pick<BuildSummary, 'name' | 'recommendedFor' | 'summary' | 'totalPrice' | 'confidence'> & { warning: string; items?: BuildItem[] }>> = {
  balanced: [
    { name: '균형형 표준 견적', recommendedFor: '균형 우선', summary: '게임과 개발을 모두 고려한 기본 추천입니다.', totalPrice: 1980000, confidence: 'MEDIUM', warning: 'PSU 여유율 확인 필요' },
    { name: '작업 병행 확장형', recommendedFor: '작업 병행', summary: '메모리와 GPU 여유를 조금 더 확보했습니다.', totalPrice: 2120000, confidence: 'HIGH', warning: 'RAM 32GB 이상 권장' },
    { name: '입문형 절충 견적', recommendedFor: '예산 절충', summary: '필수 성능을 남기고 총액을 낮춘 조합입니다.', totalPrice: 1620000, confidence: 'MEDIUM', warning: 'VRAM 한계 가능성' }
  ],
  gaming: [
    { name: 'QHD 게임 균형형', recommendedFor: 'GPU 우선', summary: 'QHD 게임 프레임과 개발 병행을 맞춘 추천입니다.', totalPrice: 1980000, confidence: 'MEDIUM', warning: 'GPU 길이와 케이스 호환 확인' },
    { name: 'QHD 고주사율 확장형', recommendedFor: '프레임 우선', summary: 'GPU 등급을 높여 고주사율 여유를 확보했습니다.', totalPrice: 2260000, confidence: 'HIGH', warning: '권장 PSU 750W 이상' },
    { name: 'QHD 예산 절충형', recommendedFor: '가격 방어', summary: '그래픽 옵션 타협을 전제로 예산을 낮췄습니다.', totalPrice: 1680000, confidence: 'MEDIUM', warning: '일부 최신 게임 옵션 타협 필요' }
  ],
  dev: [
    { name: '개발 + 게임 혼합형', recommendedFor: '작업 병행', summary: 'IDE, Docker, 게임을 함께 쓰는 기준의 추천입니다.', totalPrice: 2120000, confidence: 'HIGH', warning: 'RAM 64GB 확장 추천' },
    { name: '빌드 작업 안정형', recommendedFor: 'CPU 우선', summary: '컴파일과 멀티태스킹에 유리한 CPU/RAM 구성을 우선했습니다.', totalPrice: 2050000, confidence: 'HIGH', warning: '쿨러 높이 확인 필요' },
    { name: '개발 입문 균형형', recommendedFor: '예산 절충', summary: '개발 작업에 필요한 메모리와 SSD를 우선했습니다.', totalPrice: 1580000, confidence: 'MEDIUM', warning: 'GPU 업그레이드 여지 확인' }
  ],
  ai: [
    { name: 'CUDA 실습 균형형', recommendedFor: 'VRAM 우선', summary: 'CUDA 실습과 개발을 함께 고려한 GPU 중심 추천입니다.', totalPrice: 2480000, confidence: 'HIGH', warning: 'VRAM 요구량 큰 모델은 한계 가능' },
    { name: 'AI 실습 확장형', recommendedFor: 'AI 실습', summary: 'VRAM과 PSU 여유를 높여 실험 범위를 넓혔습니다.', totalPrice: 2980000, confidence: 'MEDIUM', warning: '케이스 GPU 장착 길이 확인' },
    { name: 'AI 입문 절충형', recommendedFor: '입문형', summary: 'CUDA 가능성을 유지하면서 총액을 낮춘 조합입니다.', totalPrice: 1860000, confidence: 'MEDIUM', warning: '대형 모델 실습에는 VRAM 부족 가능' }
  ],
  budget: [
    { name: '150만원 가성비형', recommendedFor: '가격 우선', summary: 'FHD 게임과 일반 작업 기준으로 비용 효율을 맞췄습니다.', totalPrice: 1490000, confidence: 'MEDIUM', warning: 'QHD 게임은 옵션 조정 필요' },
    { name: '가성비 업그레이드형', recommendedFor: '확장 여지', summary: '향후 GPU 업그레이드를 고려한 보드/파워 여유를 남겼습니다.', totalPrice: 1580000, confidence: 'MEDIUM', warning: '초기 GPU 성능은 중급 기준' },
    { name: '최소 예산 방어형', recommendedFor: '최저 예산', summary: '필수 부품 비용을 낮추고 안정성을 유지했습니다.', totalPrice: 1320000, confidence: 'LOW', warning: '성능 여유가 낮음' }
  ],
  quiet: [
    { name: '저소음 균형형', recommendedFor: '소음 우선', summary: '저발열 부품과 쿨링 여유를 우선한 추천입니다.', totalPrice: 2140000, confidence: 'HIGH', warning: '케이스 흡기 구조 확인' },
    { name: '저소음 작업형', recommendedFor: '작업 집중', summary: '장시간 개발 작업에서 소음과 발열을 낮추는 조합입니다.', totalPrice: 2060000, confidence: 'HIGH', warning: '팬 커브 설정 권장' },
    { name: '조용한 QHD 절충형', recommendedFor: '게임 절충', summary: 'QHD 게임 성능을 유지하면서 전력 소모를 낮췄습니다.', totalPrice: 1960000, confidence: 'MEDIUM', warning: '최고 옵션보다 저소음 우선' }
  ],
  creative: [
    { name: '영상편집 균형형', recommendedFor: '편집 작업', summary: 'CPU 멀티코어와 RAM 여유를 우선한 편집용 추천입니다.', totalPrice: 2360000, confidence: 'HIGH', warning: '작업 SSD 용량 확인' },
    { name: '4K 편집 확장형', recommendedFor: '4K 편집', summary: 'GPU 가속과 대용량 프로젝트 저장공간을 강화했습니다.', totalPrice: 2840000, confidence: 'MEDIUM', warning: '캐시 SSD 추가 권장' },
    { name: '입문 편집 절충형', recommendedFor: '예산 절충', summary: '영상 편집 입문에 필요한 RAM과 저장공간을 우선했습니다.', totalPrice: 1780000, confidence: 'MEDIUM', warning: '장시간 렌더링 발열 확인' }
  ],
  office: [
    { name: '사무/학습 안정형', recommendedFor: '안정성 우선', summary: '문서, 온라인 강의, 브라우저 작업에 맞춘 조용한 추천입니다.', totalPrice: 980000, confidence: 'HIGH', warning: '고사양 게임은 제외' },
    { name: '학습 확장형', recommendedFor: '확장 여지', summary: '개발 입문과 학습 자료 저장공간을 조금 더 확보했습니다.', totalPrice: 1240000, confidence: 'MEDIUM', warning: 'GPU 작업은 제한적' },
    { name: '최소 비용 사무형', recommendedFor: '비용 우선', summary: '일상 작업 중심으로 예산을 낮춘 구성입니다.', totalPrice: 820000, confidence: 'MEDIUM', warning: '업그레이드 여유 확인' }
  ]
};

export function HomePage() {
  const [consultation, setConsultation] = useState<ConsultationState | null>(() => readStoredConsultation());
  const [starterInput, setStarterInput] = useState('');
  const [chatInput, setChatInput] = useState('');

  useEffect(() => {
    if (consultation) {
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(consultation));
    } else {
      sessionStorage.removeItem(STORAGE_KEY);
    }
  }, [consultation]);

  function startConsultation(event?: FormEvent) {
    event?.preventDefault();
    const prompt = starterInput.trim();
    if (!prompt) return;

    const nextMode = detectMode(prompt);
    const now = new Date().toISOString();
    setConsultation({
      prompt,
      mode: nextMode,
      headline: INITIAL_ASSISTANT_REPLY,
      wizardStep: 'usage',
      answers: {},
      recommendations: buildRecommendations(nextMode, {}),
      assistantPosition: defaultAssistantPosition(),
      messages: [
        createMessage('user', prompt, now),
        createMessage('assistant', INITIAL_ASSISTANT_REPLY)
      ]
    });
  }

  function askFollowUp(event: FormEvent) {
    event.preventDefault();
    if (!consultation) return;

    const question = chatInput.trim();
    if (!question) return;

    const nextMode = detectMode(`${consultation.prompt} ${question}`, consultation.mode);
    const nextAnswers = inferAnswersFromText(question, consultation.answers);
    const assistantReply = followUpAssistantReply(nextMode);
    setChatInput('');
    setConsultation({
      ...consultation,
      prompt: `${consultation.prompt} ${question}`,
      mode: nextMode,
      headline: profileCopy[nextMode].title,
      wizardStep: nextMode === 'gaming' ? 'resolution' : 'usage',
      answers: nextAnswers,
      recommendations: buildRecommendations(nextMode, nextAnswers),
      messages: [
        ...consultation.messages,
        createMessage('user', question),
        createMessage('assistant', assistantReply)
      ]
    });
  }

  function selectWizardOption(option: WizardOption) {
    if (!consultation) return;

    const nextAnswers = {
      ...consultation.answers,
      [option.answerKey]: option.label
    };
    setConsultation({
      ...consultation,
      mode: option.mode,
      headline: option.nextStep === 'resolution' ? '게임용 기준으로 추천을 조정했습니다' : profileCopy[option.mode].title,
      wizardStep: option.nextStep,
      answers: nextAnswers,
      recommendations: buildRecommendations(option.mode, nextAnswers),
      messages: [
        ...consultation.messages,
        createMessage('user', option.label),
        createMessage('assistant', option.assistant)
      ]
    });
  }

  function updateAssistantPosition(position: AssistantPosition) {
    setConsultation((current) => current ? { ...current, assistantPosition: position } : current);
  }

  if (!consultation) {
    return (
      <Screen>
        <StarterView
          value={starterInput}
          onChange={setStarterInput}
          onPromptPick={setStarterInput}
          onSubmit={startConsultation}
        />
      </Screen>
    );
  }

  return (
    <Screen>
      <ConsultingView
        consultation={consultation}
        onReset={() => setConsultation(null)}
      />
      <AssistantBar
        messages={consultation.messages}
        value={chatInput}
        position={consultation.assistantPosition}
        wizardOptions={wizardOptionsFor(consultation.wizardStep)}
        onChange={setChatInput}
        onSubmit={askFollowUp}
        onWizardSelect={selectWizardOption}
        onPositionChange={updateAssistantPosition}
      />
    </Screen>
  );
}

function StarterView({
  value,
  onChange,
  onPromptPick,
  onSubmit
}: {
  value: string;
  onChange: (value: string) => void;
  onPromptPick: (value: string) => void;
  onSubmit: (event: FormEvent) => void;
}) {
  return (
    <div className="space-y-8 pb-10">
      <section className="grid gap-5">
        <div className="panel overflow-hidden">
          <div className="border-b border-commerce-line bg-white px-5 py-3 sm:px-7">
            <div className="flex flex-wrap items-center gap-2 text-xs font-black">
              <span className="rounded bg-commerce-sale px-2 py-1 text-white">SALE</span>
              <span className="text-commerce-ink">AI 추천 견적 · 내부 자산 가격 기준</span>
              <span className="text-slate-400">오늘 업데이트</span>
            </div>
          </div>
          <div className="grid gap-6 p-5 sm:p-7 lg:grid-cols-[minmax(0,1fr)_260px] lg:items-center">
            <div>
              <div className="mb-4 inline-flex items-center gap-2 rounded-full border border-blue-100 bg-brand-pale px-3 py-1 text-xs font-black text-brand-blue">
                <Sparkles size={14} />
                자연어로 시작하는 PC 쇼핑
              </div>
              <h1 className="break-keep text-3xl font-black leading-tight tracking-tight text-commerce-ink sm:text-5xl">
                어떤 PC 견적이 필요하세요?
              </h1>
              <p className="mt-4 max-w-2xl break-keep text-sm leading-6 text-slate-600 sm:text-base">
                예산, 게임 해상도, 개발 환경, 선호 브랜드를 입력하면 추천 견적과 부품 쇼핑 경로를 바로 정리합니다.
              </p>

              <form onSubmit={onSubmit} className="mt-7 rounded-md border-2 border-commerce-ink bg-white p-2 shadow-product">
                <label className="sr-only" htmlFor="home-starter-input">원하는 PC 사양 입력</label>
                <div className="flex flex-col gap-2 sm:flex-row sm:items-end">
                  <textarea
                    id="home-starter-input"
                    aria-label="원하는 PC 사양 입력"
                    value={value}
                    onChange={(event) => onChange(event.target.value)}
                    className="min-h-24 flex-1 resize-none rounded bg-slate-50 px-4 py-3 text-left text-sm leading-6 text-slate-900 outline-none focus:bg-white focus:ring-4 focus:ring-blue-100"
                    placeholder="예: 200만원 안에서 QHD 게임과 개발을 같이 할 PC 추천해줘. NVIDIA 선호."
                  />
                  <button
                    type="submit"
                    disabled={!value.trim()}
                    className="flex min-h-12 items-center justify-center gap-2 rounded bg-commerce-ink px-5 text-sm font-black text-white transition hover:bg-slate-700 disabled:cursor-not-allowed disabled:bg-slate-300"
                  >
                    견적 상담 시작
                    <ArrowRight size={17} />
                  </button>
                </div>
              </form>

              <div className="mt-4 flex flex-wrap gap-2">
                {starterPrompts.map((prompt) => (
                  <button
                    key={prompt.label}
                    type="button"
                    onClick={() => onPromptPick(prompt.value)}
                    className="rounded-full border border-commerce-line bg-white px-4 py-2 text-sm font-black text-slate-700 shadow-sm transition hover:border-commerce-ink hover:text-commerce-ink focus:outline-none focus:ring-4 focus:ring-blue-100"
                  >
                    {prompt.label}
                  </button>
                ))}
              </div>
            </div>

            <div className="rounded-lg border border-commerce-line bg-gradient-to-b from-slate-50 to-white p-4">
              <div className="mb-3 flex items-center justify-between gap-3">
                <div className="text-xs font-black text-slate-500">오늘의 추천 견적</div>
                <span className="rounded bg-commerce-sale px-2 py-1 text-[11px] font-black text-white">12% OFF</span>
              </div>
              <div className="grid h-28 place-items-center rounded-md bg-commerce-ink text-white">
                <ShoppingCart size={34} />
              </div>
              <h2 className="mt-4 text-lg font-black text-commerce-ink">QHD 게이밍 추천팩</h2>
              <p className="mt-1 text-xs leading-5 text-slate-500">RTX 5070 · Ryzen 7 · DDR5 32GB</p>
              <div className="mt-4 flex items-end gap-2">
                <span className="text-2xl font-black tracking-tight text-commerce-sale">1,980,000원</span>
                <span className="pb-1 text-xs font-bold text-slate-400 line-through">2,250,000원</span>
              </div>
              <div className="mt-3 flex items-center gap-2 text-xs font-bold text-commerce-green">
                <PackageCheck size={15} />
                호환성 통과
              </div>
            </div>
          </div>
        </div>

        {/* <aside className="panel p-5">
          <div className="mb-4 flex items-center justify-between gap-3">
            <div>
              <h2 className="text-lg font-black text-commerce-ink">빠른 쇼핑</h2>
              <p className="mt-1 text-xs text-slate-500">많이 찾는 부품 카테고리</p>
            </div>
            <Link to="/self-quote" aria-label="빠른 쇼핑 전체 보기" className="text-xs font-black text-brand-blue hover:underline">
              전체 보기
            </Link>
          </div>
          <div className="grid grid-cols-2 gap-2">
            {quickCategories.map((item) => (
              <Link
                key={item.label}
                aria-label={item.label}
                to={item.to}
                className="rounded-md border border-commerce-line bg-slate-50 p-3 transition hover:border-commerce-ink hover:bg-white focus:outline-none focus:ring-4 focus:ring-blue-100"
              >
                <div className="flex items-center gap-2 text-sm font-black text-commerce-ink">
                  <item.icon size={16} className="text-brand-blue" />
                  {item.label}
                </div>
                <div className="mt-1 text-[11px] leading-4 text-slate-500">{item.detail}</div>
              </Link>
            ))}
          </div>
        </aside> */}
      </section>

      <section className="panel p-5">
        <div className="mb-4 flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <h2 className="text-xl font-black text-commerce-ink">오늘의 추천 견적</h2>
            <p className="mt-1 text-sm text-slate-500">용도별로 바로 비교하고 상세 견적으로 이동합니다.</p>
          </div>
          <Link to="/requirements/new" className="text-sm font-black text-brand-blue hover:underline">AI 견적 더 보기</Link>
        </div>
        <div className="grid gap-3 md:grid-cols-3">
          {featuredBuilds.map((build) => (
            <Link key={build.name} to={build.to} className={`group rounded-lg border border-commerce-line bg-gradient-to-br ${build.tone} p-4 transition hover:-translate-y-0.5 hover:border-commerce-ink hover:shadow-product focus:outline-none focus:ring-4 focus:ring-blue-100`}>
              <div className="mb-4 flex items-center justify-between">
                <span className="rounded bg-commerce-sale px-2 py-1 text-[11px] font-black text-white">{build.tag}</span>
                <Heart size={17} className="text-slate-400 group-hover:text-commerce-sale" />
              </div>
              <h3 className="text-base font-black text-commerce-ink">{build.name}</h3>
              <p className="mt-2 min-h-10 text-xs leading-5 text-slate-500">{build.spec}</p>
              <div className="mt-4 flex flex-wrap items-end gap-2">
                <span className="text-xl font-black tracking-tight text-commerce-sale">{build.price.toLocaleString()}원</span>
                <span className="text-xs font-bold text-slate-400 line-through">{build.originalPrice.toLocaleString()}원</span>
              </div>
              <div className="mt-4 flex items-center gap-2 text-xs font-black text-commerce-green">
                <PackageCheck size={15} />
                호환성 통과
              </div>
            </Link>
          ))}
        </div>
      </section>

      <section className="panel p-5">
        <div className="mb-4 flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <h2 className="text-xl font-black text-commerce-ink">인기 부품 랭킹</h2>
            <p className="mt-1 text-sm text-slate-500">셀프 견적에서 자주 비교하는 내부 자산 카테고리입니다.</p>
          </div>
          <Link to="/self-quote" aria-label="셀프 견적 전체 보기" className="text-sm font-black text-brand-blue hover:underline">셀프 견적 전체 보기</Link>
        </div>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {popularPartDeals.map((part) => (
            <Link key={part.label} to={part.to} aria-label={`인기 부품 ${part.rank}번 보기`} className="rounded-lg border border-commerce-line bg-white p-4 transition hover:-translate-y-0.5 hover:border-commerce-ink hover:shadow-product focus:outline-none focus:ring-4 focus:ring-blue-100">
              <div className="mb-3 flex items-center justify-between">
                <span className="flex h-7 w-7 items-center justify-center rounded-full bg-commerce-ink text-xs font-black text-white">{part.rank}</span>
                <span className={`rounded px-2 py-1 text-[11px] font-black ${part.sale === 'SALE' ? 'bg-commerce-sale text-white' : 'bg-slate-100 text-slate-700'}`}>{part.sale}</span>
              </div>
              <div className="grid h-24 place-items-center rounded-md bg-slate-50 text-brand-blue">
                <part.icon size={30} />
              </div>
              <div className="mt-3 text-xs font-black text-brand-blue">{part.category}</div>
              <h3 className="mt-1 min-h-10 text-sm font-black leading-5 text-commerce-ink">{part.label}</h3>
              <p className="mt-1 text-xs text-slate-500">{part.detail}</p>
              <div className="mt-3 flex items-center justify-between gap-2">
                <span className="text-lg font-black text-commerce-ink">{part.price.toLocaleString()}원</span>
                <div className="flex items-center gap-1 text-[11px] font-bold text-amber-600">
                  <Star size={12} fill="currentColor" />
                  인기
                </div>
              </div>
            </Link>
          ))}
        </div>
      </section>
    </div>
  );
}

function ConsultingView({
  consultation,
  onReset
}: {
  consultation: ConsultationState;
  onReset: () => void;
}) {
  const copy = profileCopy[consultation.mode];

  return (
    <div className="space-y-5 pb-32 md:pb-36">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <div className="text-xs font-semibold text-slate-500">Home / AI PC consulting</div>
          <h1 className="mt-2 break-keep text-3xl font-black text-slate-950">{consultation.headline}</h1>
          <p className="mt-2 max-w-2xl break-keep text-sm leading-6 text-slate-600">{copy.summary}</p>
        </div>
        <button type="button" onClick={onReset} className="h-10 rounded border border-slate-300 bg-white px-4 text-sm font-bold text-slate-700 hover:border-brand-blue hover:text-brand-blue">
          새 상담
        </button>
      </div>

      {/* <section className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_390px]">
        <div className="panel relative overflow-hidden p-5 sm:p-7">
          <div className="absolute inset-y-0 left-0 w-1 bg-gradient-to-b from-brand-blue via-emerald-500 to-amber-400" />
          <div className="flex flex-wrap items-center gap-2">
            <span className="rounded-full border border-blue-100 bg-brand-pale px-3 py-1 text-xs font-black text-brand-blue">{copy.badge}</span>
            <span className="rounded-full border border-slate-200 bg-slate-50 px-3 py-1 text-xs font-bold text-slate-500">프론트 로컬 시뮬레이션</span>
          </div>
          <div className="mt-5 rounded-2xl border border-slate-200 bg-slate-50 p-4">
            <div className="mb-2 text-xs font-black uppercase text-slate-500">사용자 요청</div>
            <p className="break-keep text-sm leading-6 text-slate-800">{consultation.prompt}</p>
          </div>
          <div className="mt-5 grid gap-3 sm:grid-cols-3">
            <Metric label="추천안" value="3개" body="용도별 우선순위 반영" />
            <Metric label="검증 흐름" value="4단계" body="요구사항, 근거, Tool, 결과" />
            <Metric label="저장 범위" value="세션" body="탭을 닫으면 초기화" />
          </div>
        </div>

        <aside className="panel p-5 sm:p-6">
          <div className="mb-5 flex items-start justify-between gap-3">
            <div>
              <h2 className="text-lg font-black text-slate-950">검증 파이프라인</h2>
              <p className="mt-1 text-xs leading-5 text-slate-500">백엔드 변경 없이 화면 상태로만 추천 흐름을 표현합니다.</p>
            </div>
            <StatusBadge status="ACTIVE" />
          </div>
          <div className="space-y-1">
            {verificationStages.map((stage, index) => (
              <div key={stage.title} className="grid grid-cols-[32px_1fr] gap-3 py-3">
                <div className="flex flex-col items-center">
                  <div className="grid h-8 w-8 place-items-center rounded-full border border-slate-200 bg-white text-brand-blue">
                    <stage.icon size={16} />
                  </div>
                  {index < verificationStages.length - 1 ? <div className="mt-2 h-8 w-px bg-slate-200" /> : null}
                </div>
                <div>
                  <div className="flex flex-wrap items-center gap-2">
                    <h3 className="text-sm font-black text-slate-950">{stage.title}</h3>
                    <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[11px] font-bold text-slate-500">{stage.meta}</span>
                  </div>
                  <p className="mt-1 text-xs leading-5 text-slate-500">{stage.detail}</p>
                </div>
              </div>
            ))}
          </div>
        </aside>
      </section> */}

      <section className="grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_360px]">
        <div className="panel p-5">
          <div className="mb-4 flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
            <div>
              <h2 className="text-lg font-black text-slate-950">추천 컴퓨터</h2>
              <p className="mt-1 text-xs text-slate-500">질문에 따라 카드 순서와 경고가 프론트 상태에서 바뀝니다.</p>
            </div>
            <Link to="/my/quotes" className="text-sm font-bold text-brand-blue hover:underline">내 견적함 보기</Link>
          </div>
          <div className="grid gap-3 md:grid-cols-3">
            {consultation.recommendations.map((build) => (
              <BuildPreviewCard key={`${consultation.mode}-${build.id}`} build={build} />
            ))}
          </div>
        </div>

        <div className="space-y-5">
          <section className="panel p-5">
            <div className="mb-4">
              <h2 className="text-lg font-black text-slate-950">부품 바로가기</h2>
              <p className="mt-1 text-xs text-slate-500">기존 셀프 견적 흐름으로 바로 이동합니다.</p>
            </div>
            <div className="grid grid-cols-2 gap-2">
              {quickCategories.map((item) => (
                <Link
                  key={item.label}
                  aria-label={item.label}
                  to={item.to}
                  className="rounded-lg border border-slate-200 bg-slate-50 p-3 transition hover:border-brand-blue hover:bg-brand-pale focus:outline-none focus:ring-4 focus:ring-blue-100"
                >
                  <div className="flex items-center gap-2 text-sm font-black text-slate-950">
                    <item.icon size={16} className="text-brand-blue" />
                    {item.label}
                  </div>
                  <div className="mt-1 text-[11px] leading-4 text-slate-500">{item.detail}</div>
                </Link>
              ))}
            </div>
          </section>

          <section className="panel p-5">
            <div className="mb-4 flex items-center gap-2">
              <div className="grid h-9 w-9 place-items-center rounded bg-emerald-50 text-emerald-700">
                <LifeBuoy size={18} />
              </div>
              <div>
                <h2 className="text-base font-black text-slate-950">PC Agent / AS</h2>
                <p className="text-xs text-slate-500">기존 AS 접수 흐름은 그대로 둡니다.</p>
              </div>
            </div>
            <div className="space-y-2 text-xs leading-5 text-slate-600">
              <div className="flex gap-2"><CheckCircle2 size={15} className="mt-0.5 shrink-0 text-emerald-600" />최근 30분 JSONL 로그 업로드</div>
              <div className="flex gap-2"><CheckCircle2 size={15} className="mt-0.5 shrink-0 text-emerald-600" />명시 동의 후 AS 티켓 생성</div>
              <div className="flex gap-2"><CheckCircle2 size={15} className="mt-0.5 shrink-0 text-emerald-600" />관리자 화면에서 원인 후보 검토</div>
            </div>
            <Link to="/support/new" className="mt-4 flex min-h-10 items-center justify-center rounded border border-slate-300 bg-white text-sm font-bold text-slate-800 hover:border-brand-blue hover:text-brand-blue">
              AS 접수로 이동
            </Link>
          </section>
        </div>
      </section>
    </div>
  );
}

function AssistantBar({
  messages,
  value,
  position,
  wizardOptions,
  onChange,
  onSubmit,
  onWizardSelect,
  onPositionChange
}: {
  messages: ChatMessage[];
  value: string;
  position: AssistantPosition;
  wizardOptions: WizardOption[];
  onChange: (value: string) => void;
  onSubmit: (event: FormEvent) => void;
  onWizardSelect: (option: WizardOption) => void;
  onPositionChange: (position: AssistantPosition) => void;
}) {
  const dragState = useRef<{ offsetX: number; offsetY: number } | null>(null);
  const latestAssistantMessage = [...messages].reverse().find((message) => message.role === 'assistant');

  useEffect(() => {
    function moveAssistant(event: globalThis.PointerEvent) {
      if (!dragState.current || window.innerWidth < 768) return;
      const nextX = clamp(event.clientX - dragState.current.offsetX, 16, window.innerWidth - 760);
      const nextY = clamp(event.clientY - dragState.current.offsetY, 96, window.innerHeight - ASSISTANT_BAR_ESTIMATED_HEIGHT - 16);
      onPositionChange({ x: nextX, y: nextY });
    }

    function stopDrag() {
      dragState.current = null;
    }

    window.addEventListener('pointermove', moveAssistant);
    window.addEventListener('pointerup', stopDrag);
    return () => {
      window.removeEventListener('pointermove', moveAssistant);
      window.removeEventListener('pointerup', stopDrag);
    };
  }, [onPositionChange]);

  function startDrag(event: ReactPointerEvent<HTMLDivElement>) {
    if (window.innerWidth < 768) return;
    const rect = event.currentTarget.getBoundingClientRect();
    dragState.current = {
      offsetX: event.clientX - rect.left,
      offsetY: event.clientY - rect.top
    };
  }

  return (
    <section
      data-testid="assistant-bar"
      style={{
        '--assistant-left': `${position.x}px`,
        '--assistant-top': `${position.y}px`
      } as CSSProperties}
      className="fixed bottom-3 left-3 right-3 z-30 md:bottom-auto md:left-[var(--assistant-left)] md:right-auto md:top-[var(--assistant-top)] md:w-[720px]"
    >
      <div onPointerDown={startDrag} className="cursor-grab rounded-3xl border border-slate-200 bg-white p-3 shadow-[0_18px_55px_rgba(15,23,42,0.18)] active:cursor-grabbing">
        <div className="mb-2 flex items-center justify-between gap-3 px-1">
          <div className="flex min-w-0 items-center gap-2">
            <div className="grid h-9 w-9 shrink-0 place-items-center rounded-2xl bg-brand-blue text-white">
              <Bot size={18} />
            </div>
            <div className="min-w-0">
              <div className="flex items-center gap-2 text-sm font-black text-slate-950">
                BuildGraph Assistant
                <Move size={13} className="hidden text-slate-400 md:block" />
              </div>
              <p className="truncate text-xs text-slate-500">추천 조건을 고르면 메인 추천 컴퓨터가 바로 바뀝니다.</p>
            </div>
          </div>
          <StatusBadge status="ACTIVE" />
        </div>
        <div data-testid="assistant-answer" className="mb-3 rounded-2xl bg-slate-50 px-4 py-3 text-sm font-semibold leading-6 text-slate-800">
          {latestAssistantMessage?.content ?? '추천 컴퓨터를 메인화면에 제공해드렸습니다. 혹시 어떤 용도로 사용하시나요?'}
        </div>
        <div data-testid="wizard-options" className="mb-3 flex flex-wrap gap-2">
          {wizardOptions.map((option) => (
            <button
              key={`${option.answerKey}-${option.label}`}
              type="button"
              onClick={() => onWizardSelect(option)}
              onPointerDown={(event) => event.stopPropagation()}
              className="min-h-9 rounded-full border border-blue-100 bg-brand-pale px-3 text-xs font-black text-brand-blue transition hover:border-brand-blue hover:bg-white focus:outline-none focus:ring-4 focus:ring-blue-100"
            >
              {option.label}
            </button>
          ))}
        </div>
        <form onSubmit={onSubmit} className="flex items-end gap-2">
          <label className="sr-only" htmlFor="assistant-question">AI에게 추가 질문</label>
          <textarea
            id="assistant-question"
            aria-label="AI에게 추가 질문"
            value={value}
            onChange={(event) => onChange(event.target.value)}
            onPointerDown={(event) => event.stopPropagation()}
            className="max-h-28 min-h-11 flex-1 resize-none rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3 text-sm leading-5 outline-none focus:border-brand-blue focus:bg-white focus:ring-4 focus:ring-blue-100"
            placeholder="예: 저소음으로 바꿔줘, 예산을 150만원으로 낮춰줘"
          />
          <button
            type="submit"
            aria-label="질문 보내기"
            onPointerDown={(event) => event.stopPropagation()}
            className="grid h-11 w-11 shrink-0 place-items-center rounded-2xl bg-brand-blue text-white hover:bg-[#004f95] focus:outline-none focus:ring-4 focus:ring-blue-200"
          >
            <Send size={18} />
          </button>
        </form>
      </div>
    </section>
  );
}

function BuildPreviewCard({ build }: { build: BuildSummary }) {
  const primaryWarning = build.warnings?.[0]?.message;

  return (
    <article className="rounded-lg border border-slate-200 bg-white p-4">
      <div className="mb-3 flex items-start justify-between gap-3">
        <div>
          <div className="text-xs font-bold text-brand-blue">{build.recommendedFor ?? '맞춤 추천'}</div>
          <h3 className="mt-1 text-base font-black leading-5 text-slate-950">{build.name}</h3>
        </div>
        <StatusBadge status={build.confidence} />
      </div>
      <p className="min-h-10 text-xs leading-5 text-slate-500">{build.summary ?? '내부 자산과 저장된 현재가 기준으로 구성했습니다.'}</p>
      <div className="mt-4 text-xl font-black text-slate-950">{build.totalPrice.toLocaleString()}원</div>
      <div className="mt-3 rounded bg-slate-50 px-3 py-2 text-xs font-semibold text-slate-600">
        {primaryWarning ?? '주요 조건 충족'}
      </div>
      <div className="mt-4 flex flex-wrap gap-2">
        <Link to={`/builds/${build.id}`} className="rounded bg-brand-blue px-3 py-2 text-xs font-bold text-white hover:bg-[#004f95]">상세 보기</Link>
        <Link to={`/builds/${build.id}/change-part`} className="rounded border border-slate-300 px-3 py-2 text-xs font-bold text-slate-700 hover:border-brand-blue hover:text-brand-blue">부품 변경</Link>
      </div>
    </article>
  );
}

function Metric({ label, value, body }: { label: string; value: string; body: string }) {
  return (
    <div className="rounded-xl border border-slate-200 bg-white p-3">
      <div className="text-xs font-bold uppercase text-slate-500">{label}</div>
      <div className="mt-1 text-xl font-black text-slate-950">{value}</div>
      <div className="mt-1 text-[11px] leading-4 text-slate-500">{body}</div>
    </div>
  );
}

function wizardOptionsFor(step: WizardStep) {
  return step === 'resolution' ? resolutionWizardOptions : usageWizardOptions;
}

function buildRecommendations(mode: RecommendationMode, answers: Partial<Record<WizardAnswerKey, string>> = {}): BuildSummary[] {
  return recommendationTemplates[mode].map((template, index) => {
    const base = builds[index % builds.length];
    const resolutionPrefix = mode === 'gaming' && answers.resolution ? `${answers.resolution} ` : '';
    return {
      ...base,
      name: resolutionPrefix && !template.name.startsWith(resolutionPrefix) ? `${resolutionPrefix}${template.name}` : template.name,
      recommendedFor: template.recommendedFor,
      summary: template.summary,
      totalPrice: template.totalPrice,
      confidence: template.confidence,
      warnings: [{ message: template.warning, severity: template.confidence === 'LOW' ? 'WARN' : 'INFO' }],
      items: template.items ?? base.items,
      toolResults: toolResultsFor(mode)
    };
  });
}

function toolResultsFor(mode: RecommendationMode): ToolResult[] {
  const warningSummary = mode === 'quiet'
    ? '쿨러와 케이스 흡기 구조를 우선 확인합니다.'
    : mode === 'ai'
      ? 'GPU 길이와 권장 PSU 용량을 함께 확인합니다.'
      : mode === 'creative'
        ? '장시간 렌더링 기준 발열과 저장공간 여유를 확인합니다.'
        : '호환성, 전력, 규격 검증을 통과했습니다.';

  return [
    { tool: 'compatibility', status: 'PASS', confidence: 'HIGH', summary: 'CPU, 메인보드, RAM 조합 기준 호환됩니다.' },
    { tool: 'power', status: mode === 'budget' ? 'WARN' : 'PASS', confidence: 'MEDIUM', summary: warningSummary },
    { tool: 'price', status: 'WARN', confidence: 'MEDIUM', summary: '표시 가격 기준 예산 범위에 맞춰 정렬했습니다.' }
  ];
}

function detectMode(text: string, fallback: RecommendationMode = 'balanced'): RecommendationMode {
  const normalized = text.toLowerCase();
  if (/저소음|조용|소음|무소음/.test(normalized)) return 'quiet';
  if (/ai|cuda|딥러닝|머신러닝|학습/.test(normalized)) return 'ai';
  if (/영상|편집|렌더|프리미어|다빈치/.test(normalized)) return 'creative';
  if (/사무|학습|강의|문서|엑셀|office/.test(normalized)) return 'office';
  if (/qhd|게임|game|고주사율|fps/.test(normalized)) return 'gaming';
  if (/개발|코딩|docker|도커|ide|빌드/.test(normalized)) return 'dev';
  if (/150|가성비|저렴|최소|예산 낮/.test(normalized)) return 'budget';
  return fallback;
}

function inferAnswersFromText(text: string, answers: Partial<Record<WizardAnswerKey, string>>) {
  const mode = detectMode(text);
  const nextAnswers = { ...answers };
  if (mode !== 'balanced') {
    nextAnswers.usage = profileCopy[mode].badge;
  }
  if (/fhd/i.test(text)) nextAnswers.resolution = 'FHD';
  if (/qhd/i.test(text)) nextAnswers.resolution = 'QHD';
  if (/4k/i.test(text)) nextAnswers.resolution = '4K';
  if (/고주사율|fps/i.test(text)) nextAnswers.resolution = '고주사율';
  return nextAnswers;
}

function followUpAssistantReply(mode: RecommendationMode) {
  if (mode === 'gaming') return '게임용 기준으로 추천을 조정했습니다. 주로 어떤 해상도로 플레이하시나요?';
  return `${profileCopy[mode].badge} 기준으로 추천 컴퓨터를 다시 정리했습니다. ${profileCopy[mode].assistant}`;
}

function createMessage(role: ChatMessage['role'], content: string, createdAt = new Date().toISOString()): ChatMessage {
  return {
    id: `${role}-${createdAt}-${Math.random().toString(16).slice(2)}`,
    role,
    content,
    createdAt
  };
}

function readStoredConsultation(): ConsultationState | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as ConsultationState;
    if (!parsed.prompt || !parsed.mode || !Array.isArray(parsed.messages)) return null;
    const answers = parsed.answers ?? {};
    const recommendations = Array.isArray(parsed.recommendations) && parsed.recommendations.length > 0
      ? parsed.recommendations
      : buildRecommendations(parsed.mode, answers);
    return {
      ...parsed,
      headline: parsed.headline ?? profileCopy[parsed.mode].title,
      wizardStep: parsed.wizardStep ?? 'usage',
      answers,
      recommendations,
      assistantPosition: parsed.assistantPosition ?? defaultAssistantPosition()
    };
  } catch {
    return null;
  }
}

function defaultAssistantPosition(): AssistantPosition {
  if (typeof window === 'undefined') {
    return { x: 360, y: 720 };
  }
  return {
    x: Math.max(16, Math.floor(window.innerWidth / 2 - 360)),
    y: Math.max(96, window.innerHeight - ASSISTANT_BAR_ESTIMATED_HEIGHT - 16)
  };
}

function clamp(value: number, min: number, max: number) {
  if (max < min) return min;
  return Math.min(Math.max(value, min), max);
}
