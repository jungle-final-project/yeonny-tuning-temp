import { useMutation, useQuery } from '@tanstack/react-query';
import { ArrowLeft, CalendarDays, ChevronDown, ClipboardCheck, ExternalLink, MapPin, ShoppingBag, Truck } from 'lucide-react';
import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import type React from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Panel, Screen, StateMessage } from '../../../components/ui';
import { getCurrentUser } from '../../auth/authApi';
import { resolveBuildGraph } from '../../quote/quoteApi';
import type { BuildGraphResolveResponse } from '../../quote/aiSelection';
import { createAssemblyRequest, type AssemblyDeliveryMethod, type AssemblyServiceType } from '../assemblyApi';
import { handlePartImageError, partImageUrl, partShortSpec } from '../partDisplay';
import { getCurrentQuoteDraft } from '../partsApi';
import type { QuoteDraftItem } from '../types';

const KAKAO_POSTCODE_SCRIPT_ID = 'kakao-postcode-script';
const KAKAO_POSTCODE_SCRIPT_URL = 'https://t1.kakaocdn.net/mapjsapi/bundle/postcode/prod/postcode.v2.js';

const CATEGORY_LABELS: Record<string, string> = {
  CPU: 'CPU',
  MOTHERBOARD: '메인보드',
  RAM: 'RAM',
  GPU: 'GPU',
  STORAGE: 'SSD',
  PSU: '파워',
  CASE: '케이스',
  COOLER: '쿨러'
};

const ASSEMBLY_REGIONS = ['서울', '경기', '인천', '대전', '대구', '부산', '광주'];

type KakaoPostcodeData = {
  zonecode: string;
  address: string;
  roadAddress: string;
  jibunAddress: string;
  userSelectedType: 'R' | 'J';
  bname: string;
  buildingName: string;
  apartment: string;
};

type KakaoPostcodeConstructor = new (options: {
  oncomplete: (data: KakaoPostcodeData) => void;
}) => {
  open: () => void;
};

let kakaoPostcodeScriptPromise: Promise<void> | null = null;

export function CheckoutPage() {
  const navigate = useNavigate();
  const preferredDateInputRef = useRef<HTMLInputElement>(null);
  const addressLine2InputRef = useRef<HTMLInputElement>(null);
  const contactDefaultsAppliedRef = useRef(false);
  const [serviceType, setServiceType] = useState<AssemblyServiceType>('FULL_SERVICE');
  const [region, setRegion] = useState('서울');
  const [preferredDate, setPreferredDate] = useState(defaultPreferredDate);
  const [deliveryMethod, setDeliveryMethod] = useState<AssemblyDeliveryMethod>('DELIVERY');
  const [contactName, setContactName] = useState('');
  const [contactPhone, setContactPhone] = useState('');
  const [postalCode, setPostalCode] = useState('');
  const [addressLine1, setAddressLine1] = useState('');
  const [addressLine2, setAddressLine2] = useState('');
  const [note, setNote] = useState('');
  const [asPolicyAccepted, setAsPolicyAccepted] = useState(false);
  const [isPartListOpen, setIsPartListOpen] = useState(true);
  const [isRequestInfoOpen, setIsRequestInfoOpen] = useState(true);
  const [formError, setFormError] = useState<string | null>(null);
  const [addressSearchError, setAddressSearchError] = useState('');
  const [idempotencyKey] = useState(() => crypto.randomUUID());
  const { data: quoteDraft, isLoading, isError } = useQuery({
    queryKey: ['quote-draft', 'current'],
    queryFn: getCurrentQuoteDraft
  });
  const graphQuery = useQuery({
    queryKey: ['checkout', 'build-graph', quoteDraft?.id, quoteDraft?.items.map((item) => `${item.partId}:${item.quantity}`).join('|')],
    queryFn: () => resolveBuildGraph({ source: 'QUOTE_DRAFT_CURRENT', view: 'FULL' }),
    enabled: Boolean(quoteDraft?.items.length),
    retry: false
  });
  const currentUserQuery = useQuery({
    queryKey: ['checkout', 'current-user-contact-defaults'],
    queryFn: getCurrentUser,
    retry: false,
    staleTime: 60_000
  });
  const createRequestMutation = useMutation({
    mutationFn: () => createAssemblyRequest({
      serviceType,
      region,
      preferredDate,
      deliveryMethod,
      note: note.trim(),
      asPolicyAccepted,
      contactName: contactName.trim(),
      contactPhone: contactPhone.trim(),
      postalCode: postalCode.trim(),
      addressLine1: addressLine1.trim(),
      addressLine2: addressLine2.trim()
    }, idempotencyKey),
    onSuccess: (request) => navigate(`/checkout/offers/${request.id}`),
    onError: (error) => setFormError(error instanceof Error ? error.message : '조립 요청을 저장하지 못했습니다.')
  });

  useEffect(() => {
    const user = currentUserQuery.data;
    if (!user || contactDefaultsAppliedRef.current) {
      return;
    }

    contactDefaultsAppliedRef.current = true;
    setContactName((current) => current.trim() ? current : user.name);
    setContactPhone((current) => current.trim() ? current : (user.phoneNumber ?? ''));
    setPostalCode((current) => current.trim() ? current : (user.postalCode ?? ''));
    setAddressLine1((current) => current.trim() ? current : (user.addressLine1 ?? ''));
    setAddressLine2((current) => current.trim() ? current : (user.addressLine2 ?? ''));
  }, [currentUserQuery.data]);

  if (isLoading) {
    return (
      <Screen>
        <div className="rounded-lg border border-commerce-line bg-white p-8 text-sm font-bold text-slate-500">구매 확인 정보를 불러오는 중입니다.</div>
      </Screen>
    );
  }

  if (isError || !quoteDraft) {
    return (
      <Screen>
        <StateMessage type="warn" title="구매 확인 정보 조회 실패" body="현재 견적 장바구니를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요." />
      </Screen>
    );
  }

  if (quoteDraft.items.length === 0) {
    return (
      <Screen>
        <EmptyCheckout />
      </Screen>
    );
  }

  const hasCompatibilityFail = graphHasBlockingFail(graphQuery.data);
  const contactInfoComplete = Boolean(contactName.trim() && contactPhone.trim());
  const deliveryAddressComplete = deliveryMethod === 'PICKUP' || Boolean(postalCode.trim() && addressLine1.trim() && addressLine2.trim());
  const requestInfoComplete = Boolean(region && preferredDate && contactInfoComplete && deliveryAddressComplete);
  const requestInfoErrorMessage = deliveryMethod === 'DELIVERY'
    ? '수령인, 연락처, 우편번호, 주소, 상세주소와 조립 지역, 희망 일정을 모두 입력해 주세요.'
    : '수령인, 연락처와 조립 지역, 희망 일정을 모두 입력해 주세요.';
  const formComplete = requestInfoComplete && asPolicyAccepted;
  const canSubmit = formComplete && !graphQuery.isLoading && !graphQuery.isError && !hasCompatibilityFail && !createRequestMutation.isPending;
  const submitDisabledReason = createRequestMutation.isPending
    ? null
    : !requestInfoComplete
      ? '정보를 모두 입력해주세요'
      : !asPolicyAccepted
        ? '표준 AS 동의 후 요청할 수 있습니다'
        : graphQuery.isLoading
          ? '호환성 검증 중입니다'
          : graphQuery.isError || hasCompatibilityFail
            ? '호환성 검증을 통과한 견적만 요청할 수 있습니다'
            : null;

  const openPreferredDatePicker = () => {
    const input = preferredDateInputRef.current;
    if (!input) return;

    input.focus();
    try {
      (input as HTMLInputElement & { showPicker?: () => void }).showPicker?.();
    } catch {
      // Keep the focused input fallback for browsers that expose but reject showPicker().
    }
  };

  const openAddressSearch = async () => {
    setAddressSearchError('');
    try {
      await loadKakaoPostcodeScript();
      const Postcode = getKakaoPostcodeConstructor();
      if (!Postcode) {
        throw new Error('Kakao postcode is unavailable.');
      }
      new Postcode({
        oncomplete(data) {
          setPostalCode(data.zonecode);
          setAddressLine1(selectedAddressFromPostcode(data));
          addressLine2InputRef.current?.focus();
        }
      }).open();
    } catch {
      setAddressSearchError('주소 검색 서비스를 불러오지 못했습니다. 네트워크 연결을 확인해 주세요.');
    }
  };

  const createRequest = () => {
    if (!requestInfoComplete) {
      setIsRequestInfoOpen(true);
      setFormError(requestInfoErrorMessage);
      return;
    }
    if (!asPolicyAccepted) {
      setIsRequestInfoOpen(true);
      setFormError('표준 AS 동의 후 요청할 수 있습니다.');
      return;
    }
    if (graphQuery.isError || hasCompatibilityFail) {
      setFormError('호환성 검증을 통과한 견적만 실제 조립 요청을 만들 수 있습니다.');
      return;
    }
    setFormError(null);
    createRequestMutation.mutate();
  };

  return (
    <Screen>
      <div className="mb-5">
        <Link to="/self-quote" className="inline-flex items-center gap-2 text-sm font-black text-brand-blue hover:underline">
          <ArrowLeft size={16} />
          셀프 견적으로 돌아가기
        </Link>
        <h1 className="mt-3 text-3xl font-black tracking-tight text-commerce-ink">조립 견적 요청</h1>
        <p className="mt-2 max-w-2xl break-keep text-sm leading-6 text-slate-600">
          현재 견적을 기준으로 기사에게 재고 확인과 조립·배송 제안을 요청합니다.
        </p>
      </div>

      <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_380px]">
        <div className="min-w-0 space-y-5">
          <Panel
            title={`조립 대상 부품 ${quoteDraft.itemCount}개`}
            subtitle="기사는 이 구성을 기준으로 재고와 최종 가격을 다시 확인합니다."
            action={(
              <TogglePanelButton
                controlsId="checkout-part-list"
                label="조립 대상 부품"
                open={isPartListOpen}
                onClick={() => setIsPartListOpen((current) => !current)}
              />
            )}
          >
            <CollapsiblePanelBody id="checkout-part-list" open={isPartListOpen}>
              <div className="space-y-3">
                {quoteDraft.items.map((item) => <CheckoutItemCard key={item.partId} item={item} />)}
              </div>
            </CollapsiblePanelBody>
          </Panel>
          <Panel
            title="조립 요청 정보"
            subtitle="요청을 등록하면 현재 견적과 기사 제안이 계정에 저장됩니다."
            action={(
              <TogglePanelButton
                controlsId="checkout-request-info"
                label="조립 요청 정보"
                open={isRequestInfoOpen}
                onClick={() => setIsRequestInfoOpen((current) => !current)}
              />
            )}
          >
            <CollapsiblePanelBody id="checkout-request-info" open={isRequestInfoOpen}>
              <form
                className="space-y-5"
                onSubmit={(event) => {
                  event.preventDefault();
                  createRequest();
                }}
              >
              <fieldset>
                <legend className="text-sm font-black text-commerce-ink">서비스 방식</legend>
                <div className="mt-2 grid gap-2 sm:grid-cols-2" role="radiogroup" aria-label="서비스 방식">
                  <ChoiceButton
                    label="부품 구매 + 조립 + 배송"
                    description="기사가 재고와 가격을 확인합니다."
                    selected={serviceType === 'FULL_SERVICE'}
                    onClick={() => setServiceType('FULL_SERVICE')}
                  />
                  <ChoiceButton
                    label="조립만"
                    description="사용자가 준비한 부품을 조립합니다."
                    selected={serviceType === 'ASSEMBLY_ONLY'}
                    onClick={() => setServiceType('ASSEMBLY_ONLY')}
                  />
                </div>
              </fieldset>

              <fieldset className="space-y-3">
                <legend className="text-sm font-black text-commerce-ink">연락 및 수령 정보</legend>
                <div className="grid gap-3 sm:grid-cols-2">
                  <CheckoutTextInput label="수령인" value={contactName} onChange={setContactName} placeholder="이름 입력" required />
                  <CheckoutTextInput label="연락처" value={contactPhone} onChange={setContactPhone} placeholder="연락처 입력" required />
                </div>
                {deliveryMethod === 'DELIVERY' ? (
                  <div className="grid gap-3 sm:grid-cols-[minmax(0,260px)_minmax(0,1fr)]">
                    <div className="text-xs font-black text-slate-600">
                      <label htmlFor="checkout-postal-code">우편번호<RequiredMark /></label>
                      <div className="mt-1.5 flex gap-2">
                        <input
                          id="checkout-postal-code"
                          value={postalCode}
                          placeholder="주소 찾기"
                          readOnly
                          required
                          autoComplete="postal-code"
                          className="h-11 min-w-0 flex-1 rounded-md border border-commerce-line bg-slate-50 px-3 text-sm font-bold text-commerce-ink outline-none focus:border-brand-blue focus:ring-4 focus:ring-blue-100"
                        />
                        <button
                          type="button"
                          onClick={openAddressSearch}
                          className="h-11 shrink-0 rounded-md border border-brand-blue bg-white px-3 text-sm font-black text-brand-blue transition hover:bg-blue-50 focus:outline-none focus:ring-4 focus:ring-blue-100"
                        >
                          주소 찾기
                        </button>
                      </div>
                    </div>
                    <label className="block text-xs font-black text-slate-600">
                      주소<RequiredMark />
                      <input
                        value={addressLine1}
                        placeholder="주소 찾기"
                        readOnly
                        required
                        autoComplete="address-line1"
                        className="mt-1.5 h-11 w-full rounded-md border border-commerce-line bg-slate-50 px-3 text-sm font-bold text-commerce-ink outline-none focus:border-brand-blue focus:ring-4 focus:ring-blue-100"
                      />
                    </label>
                    <div className="sm:col-start-2">
                      <CheckoutTextInput inputRef={addressLine2InputRef} label="상세 주소" value={addressLine2} onChange={setAddressLine2} placeholder="동·호수 등" required autoComplete="address-line2" />
                    </div>
                    {addressSearchError ? <p className="text-xs font-bold text-red-600 sm:col-span-2">{addressSearchError}</p> : null}
                  </div>
                ) : null}
                <p className="text-xs font-bold leading-5 text-slate-500">
                  입력한 정보는 선택 기사에게 가상 결제 완료 후에만 공개됩니다.
                </p>
              </fieldset>

              <div className="grid gap-4 md:grid-cols-2">
                <label className="text-sm font-black text-commerce-ink">
                  조립 지역
                  <span className="mt-2 flex min-h-11 items-center gap-2 rounded-md border border-commerce-line bg-white px-3">
                    <MapPin size={16} className="text-slate-400" />
                    <select value={region} onChange={(event) => setRegion(event.target.value)} className="min-w-0 flex-1 bg-transparent text-sm font-bold outline-none" aria-label="조립 지역">
                      <option value="">지역 선택</option>
                      {ASSEMBLY_REGIONS.map((item) => <option key={item} value={item}>{item}</option>)}
                    </select>
                  </span>
                </label>
                <label className="text-sm font-black text-commerce-ink">
                  희망 일정
                  <span className="mt-2 flex min-h-11 cursor-pointer items-center gap-2 rounded-md border border-commerce-line bg-white px-3 focus-within:border-brand-blue focus-within:ring-4 focus-within:ring-blue-100" onClick={openPreferredDatePicker}>
                    <CalendarDays size={16} className="text-slate-400" />
                    <input ref={preferredDateInputRef} type="date" min={todayInputValue()} value={preferredDate} onChange={(event) => setPreferredDate(event.target.value)} className="min-w-0 flex-1 cursor-pointer bg-transparent text-sm font-bold outline-none" aria-label="희망 일정" />
                  </span>
                </label>
              </div>

              <fieldset>
                <legend className="text-sm font-black text-commerce-ink">수령 방식</legend>
                <div className="mt-2 grid gap-2 sm:grid-cols-2" role="radiogroup" aria-label="수령 방식">
                  <ChoiceButton label="택배 배송" description="조립 완료 후 안전 포장해 발송합니다." selected={deliveryMethod === 'DELIVERY'} onClick={() => setDeliveryMethod('DELIVERY')} />
                  <ChoiceButton label="방문 수령" description="기사와 일정을 확정한 뒤 직접 수령합니다." selected={deliveryMethod === 'PICKUP'} onClick={() => setDeliveryMethod('PICKUP')} />
                </div>
              </fieldset>

              <label className="block text-sm font-black text-commerce-ink">
                요청사항
                <textarea value={note} onChange={(event) => setNote(event.target.value)} maxLength={1000} rows={3} placeholder="선정리, 저소음 세팅 등 요청사항" className="mt-2 w-full resize-y rounded-md border border-commerce-line bg-white px-3 py-2 text-sm font-medium outline-none focus:border-brand-blue focus:ring-4 focus:ring-blue-100" />
              </label>

              <label className="flex cursor-pointer items-start gap-3 rounded-md border border-commerce-line bg-slate-50 p-4 text-sm">
                <input type="checkbox" checked={asPolicyAccepted} onChange={(event) => setAsPolicyAccepted(event.target.checked)} className="mt-0.5 h-4 w-4 accent-blue-600" />
                <span><strong className="font-black text-commerce-ink">BuildGraph 표준 AS 정책 적용에 동의합니다.</strong><span className="mt-1 block text-xs font-bold leading-5 text-slate-500">선택 가능한 모든 기사는 동일한 표준 AS 정책을 따릅니다.</span></span>
              </label>
              {formError ? <div className="rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm font-bold text-red-700">{formError}</div> : null}
              </form>
            </CollapsiblePanelBody>
          </Panel>
        </div>

        <aside className="min-w-0 xl:sticky xl:top-5 xl:self-start">
          <section className="overflow-hidden rounded-lg border border-commerce-line bg-white shadow-product">
            <div className="border-b border-commerce-line bg-[#de6c2d] px-5 py-4 text-white">
              <div className="flex items-center gap-2 text-sm font-black">
                <ClipboardCheck size={18} />
                조립 요청 요약
              </div>
              <p className="mt-1 text-xs font-bold text-white/65">기사 제안 전 현재 입력 내용을 확인하세요.</p>
            </div>
            <div className="space-y-4 p-5">
              <SummaryRow label="서비스" value={serviceType === 'FULL_SERVICE' ? '구매 + 조립 + 배송' : '조립만'} />
              <SummaryRow label="지역" value={region || '선택 필요'} muted={!region} />
              <SummaryRow label="희망 일정" value={preferredDate || '선택 필요'} muted={!preferredDate} />
              <SummaryRow label="수령" value={deliveryMethod === 'DELIVERY' ? '택배 배송' : '방문 수령'} />
              <SummaryRow label="연락처" value={contactPhone || '선택 입력'} muted={!contactPhone} />
              <div className="border-t border-commerce-line pt-4">
                <SummaryRow label="예상가" value={`${quoteDraft.totalPrice.toLocaleString()}원`} strong />
              </div>
              <SubmitRequestButton
                disabled={!canSubmit}
                disabledReason={submitDisabledReason}
                pending={createRequestMutation.isPending}
                onClick={createRequest}
              />
              <Link to="/my/assembly-requests" className="flex min-h-11 items-center justify-center rounded-md border border-commerce-line bg-white px-4 text-sm font-black text-commerce-ink hover:border-commerce-ink">
                내 조립 요청 이력
              </Link>
            </div>
          </section>
        </aside>
      </div>
    </Screen>
  );
}

const COLLAPSIBLE_TRANSITION_MS = 520;
const COLLAPSIBLE_REDUCED_TRANSITION_MS = 260;

function CollapsiblePanelBody({ id, open, children }: { id: string; open: boolean; children: React.ReactNode }) {
  const contentRef = useRef<HTMLDivElement>(null);
  const isInitialRenderRef = useRef(true);
  const [shouldRender, setShouldRender] = useState(open);
  const [height, setHeight] = useState(open ? 'auto' : '0px');
  const [isVisible, setIsVisible] = useState(open);

  useEffect(() => {
    if (open) {
      setShouldRender(true);
    }
  }, [open]);

  useLayoutEffect(() => {
    if (!shouldRender) {
      return;
    }

    const element = contentRef.current;
    if (!element) {
      return;
    }

    if (isInitialRenderRef.current) {
      isInitialRenderRef.current = false;
      setHeight(open ? 'auto' : '0px');
      setIsVisible(open);
      return;
    }

    const measuredHeight = `${element.scrollHeight}px`;
    const transitionMs = collapsibleTransitionMs();

    if (open) {
      setHeight('0px');
      setIsVisible(false);

      const frame = window.requestAnimationFrame(() => {
        setHeight(`${element.scrollHeight}px`);
        setIsVisible(true);
      });
      const settleTimer = window.setTimeout(() => setHeight('auto'), transitionMs);
      return () => {
        window.cancelAnimationFrame(frame);
        window.clearTimeout(settleTimer);
      };
    }

    setHeight(measuredHeight);
    setIsVisible(true);

    const frame = window.requestAnimationFrame(() => {
      setHeight('0px');
      setIsVisible(false);
    });
    const unmountTimer = window.setTimeout(() => setShouldRender(false), transitionMs);
    return () => {
      window.cancelAnimationFrame(frame);
      window.clearTimeout(unmountTimer);
    };
  }, [children, open, shouldRender]);

  if (!shouldRender) {
    return <div id={id} hidden />;
  }

  const inertProps = !open ? ({ inert: '' } as Record<string, string>) : {};
  const transitionMs = collapsibleTransitionMs();
  const opacityTransitionMs = Math.min(320, transitionMs);
  const transition = `height ${transitionMs}ms cubic-bezier(0.4, 0, 0.2, 1), opacity ${opacityTransitionMs}ms ease-in-out`;
  const handleTransitionEnd = (event: React.TransitionEvent<HTMLDivElement>) => {
    if (event.target !== event.currentTarget || event.propertyName !== 'height') {
      return;
    }

    if (open) {
      setHeight('auto');
      return;
    }

    setShouldRender(false);
  };

  return (
    <div
      id={id}
      aria-hidden={!open}
      style={{ height, opacity: isVisible ? 1 : 0, transition }}
      className="overflow-hidden"
      onTransitionEnd={handleTransitionEnd}
      {...inertProps}
    >
      <div ref={contentRef} className="min-h-0 overflow-hidden">
        {children}
      </div>
    </div>
  );
}

function TogglePanelButton({ controlsId, label, open, onClick }: { controlsId: string; label: string; open: boolean; onClick: () => void }) {
  const actionLabel = open ? `${label} 접기` : `${label} 펼치기`;

  return (
    <button
      type="button"
      aria-controls={controlsId}
      aria-expanded={open}
      aria-label={actionLabel}
      title={actionLabel}
      onClick={onClick}
      className="inline-flex min-h-9 items-center justify-center gap-1.5 rounded-md border border-commerce-line bg-white px-3 text-xs font-black text-commerce-ink transition hover:border-brand-blue hover:bg-blue-50 hover:text-brand-blue focus:outline-none focus:ring-4 focus:ring-blue-100"
    >
      <span>{open ? '접기' : '펼치기'}</span>
      <ChevronDown size={16} className={`transition-transform duration-300 ease-out motion-reduce:transition-none ${open ? 'rotate-180' : ''}`} />
    </button>
  );
}

function SubmitRequestButton({
  disabled,
  disabledReason,
  pending,
  onClick
}: {
  disabled: boolean;
  disabledReason: string | null;
  pending: boolean;
  onClick: () => void;
}) {
  const tooltipId = disabledReason ? 'checkout-submit-disabled-reason' : undefined;

  return (
    <div
      className="group relative"
      tabIndex={disabledReason ? 0 : undefined}
      aria-describedby={tooltipId}
    >
      <button
        type="button"
        onClick={onClick}
        disabled={disabled}
        aria-describedby={tooltipId}
        className="flex min-h-12 w-full items-center justify-center gap-2 rounded-md bg-[#de6c2d] px-4 py-3 text-sm font-black text-white transition hover:bg-[#c45c22] disabled:pointer-events-none disabled:cursor-not-allowed disabled:bg-slate-300"
      >
        <Truck size={17} />
        {pending ? '요청 저장 중...' : '기사 제안 요청하기'}
      </button>
      {disabledReason ? (
        <div
          id={tooltipId}
          role="tooltip"
          className="pointer-events-none absolute bottom-full left-1/2 z-20 mb-2 w-max max-w-[240px] -translate-x-1/2 rounded-md bg-slate-950 px-3 py-2 text-center text-xs font-black leading-5 text-white opacity-0 shadow-lg transition-opacity duration-150 group-hover:opacity-100 group-focus:opacity-100"
        >
          {disabledReason}
          <span className="absolute left-1/2 top-full h-2 w-2 -translate-x-1/2 -translate-y-1 rotate-45 bg-slate-950" />
        </div>
      ) : null}
    </div>
  );
}

function collapsibleTransitionMs() {
  return typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
    ? COLLAPSIBLE_REDUCED_TRANSITION_MS
    : COLLAPSIBLE_TRANSITION_MS;
}

function EmptyCheckout() {
  return (
    <section className="rounded-lg border border-dashed border-commerce-line bg-white p-8 text-center">
      <div className="mx-auto grid h-14 w-14 place-items-center rounded-lg bg-slate-100 text-slate-500">
        <ShoppingBag size={24} />
      </div>
      <h1 className="mt-4 text-2xl font-black text-commerce-ink">조립할 부품이 없습니다</h1>
      <p className="mx-auto mt-2 max-w-xl break-keep text-sm leading-6 text-slate-600">
        셀프 견적에서 부품을 담으면 조립 요청서와 기사 제안을 확인할 수 있습니다.
      </p>
      <Link to="/self-quote" className="mt-5 inline-flex min-h-11 items-center justify-center rounded-md bg-commerce-ink px-5 text-sm font-black text-white hover:bg-slate-700">
        셀프 견적으로 돌아가기
      </Link>
    </section>
  );
}

function CheckoutItemCard({ item }: { item: QuoteDraftItem }) {
  const offerUrl = item.externalOffer?.offerUrl;
  const supplierName = item.externalOffer?.supplierName ?? '저장된 구매처 없음';
  return (
    <article className="grid gap-3 rounded-lg border border-commerce-line bg-white p-3 sm:grid-cols-[88px_minmax(0,1fr)]">
      <img src={partImageUrl(item)} alt={`${item.name} 제품 사진`} onError={(event) => handlePartImageError(event, item.category)} className="h-24 w-full rounded-md border border-commerce-line bg-slate-50 object-contain sm:w-24" />
      <div className="min-w-0">
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <span className="rounded bg-slate-100 px-2 py-1 text-[11px] font-black text-slate-700">{CATEGORY_LABELS[item.category] ?? item.category}</span>
              <span className="text-xs font-bold text-slate-500">{item.manufacturer ?? '제조사 미확인'}</span>
            </div>
            <h3 className="mt-2 break-keep text-base font-black leading-6 text-commerce-ink">{item.name}</h3>
            <p className="mt-1 break-keep text-xs font-bold text-slate-500">{partShortSpec(item)}</p>
          </div>
          <div className="text-left sm:text-right">
            <div className="text-xs font-bold text-slate-500">수량 {item.quantity}개</div>
            <div className="mt-1 text-lg font-black text-commerce-sale">{item.lineTotal.toLocaleString()}원</div>
          </div>
        </div>
        <div className="mt-3 flex flex-col gap-2 rounded-md bg-slate-50 p-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <div className="text-[11px] font-black text-slate-400">구매처</div>
            <div className="mt-1 text-sm font-black text-commerce-ink">{supplierName}</div>
          </div>
          {offerUrl ? (
            <a
              href={offerUrl}
              target="_blank"
              rel="noreferrer"
              aria-label={`${item.name} 구매처 이동`}
              className="inline-flex min-h-10 items-center justify-center gap-2 rounded-md border border-commerce-line bg-white px-3 text-sm font-black text-brand-blue hover:border-brand-blue"
            >
              구매처 이동
              <ExternalLink size={15} />
            </a>
          ) : (
            <button type="button" disabled aria-label={`${item.name} 구매처 정보 없음`} className="inline-flex min-h-10 items-center justify-center gap-2 rounded-md border border-slate-200 bg-white px-3 text-sm font-black text-slate-300">
              구매처 정보 없음
            </button>
          )}
        </div>
      </div>
    </article>
  );
}

function ChoiceButton({ label, description, selected, onClick }: { label: string; description: string; selected: boolean; onClick: () => void }) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={selected}
      onClick={onClick}
      className={`min-h-20 rounded-md border p-3 text-left transition ${selected ? 'border-brand-blue bg-blue-50 ring-2 ring-blue-100' : 'border-commerce-line bg-white hover:border-slate-400'}`}
    >
      <span className={`block text-sm font-black ${selected ? 'text-brand-blue' : 'text-commerce-ink'}`}>{label}</span>
      <span className="mt-1 block break-keep text-xs font-bold leading-5 text-slate-500">{description}</span>
    </button>
  );
}

function CheckoutTextInput({
  label,
  value,
  onChange,
  placeholder,
  required = false,
  inputRef,
  autoComplete
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
  required?: boolean;
  inputRef?: React.Ref<HTMLInputElement>;
  autoComplete?: string;
}) {
  return (
    <label className="block text-xs font-black text-slate-600">
      {label}{required ? <RequiredMark /> : null}
      <input
        ref={inputRef}
        aria-label={label}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        placeholder={placeholder}
        required={required}
        autoComplete={autoComplete}
        className="mt-1.5 h-11 w-full rounded-md border border-commerce-line bg-white px-3 text-sm font-bold text-commerce-ink outline-none focus:border-brand-blue focus:ring-4 focus:ring-blue-100"
      />
    </label>
  );
}

function RequiredMark() {
  return <span aria-hidden="true" className="ml-0.5 align-super text-[10px] font-black leading-none text-red-500">*</span>;
}

function getKakaoPostcodeConstructor() {
  return (window as Window & { kakao?: { Postcode?: KakaoPostcodeConstructor } }).kakao?.Postcode;
}

function loadKakaoPostcodeScript() {
  if (getKakaoPostcodeConstructor()) {
    return Promise.resolve();
  }
  if (kakaoPostcodeScriptPromise) {
    return kakaoPostcodeScriptPromise;
  }

  kakaoPostcodeScriptPromise = new Promise<void>((resolve, reject) => {
    const existingScript = document.getElementById(KAKAO_POSTCODE_SCRIPT_ID) as HTMLScriptElement | null;
    if (existingScript) {
      existingScript.addEventListener('load', () => resolve(), { once: true });
      existingScript.addEventListener('error', () => {
        kakaoPostcodeScriptPromise = null;
        reject(new Error('Kakao postcode script failed to load.'));
      }, { once: true });
      return;
    }

    const script = document.createElement('script');
    script.id = KAKAO_POSTCODE_SCRIPT_ID;
    script.src = KAKAO_POSTCODE_SCRIPT_URL;
    script.async = true;
    script.onload = () => resolve();
    script.onerror = () => {
      kakaoPostcodeScriptPromise = null;
      script.remove();
      reject(new Error('Kakao postcode script failed to load.'));
    };
    document.head.appendChild(script);
  });

  return kakaoPostcodeScriptPromise;
}

function selectedAddressFromPostcode(data: KakaoPostcodeData) {
  const baseAddress = data.userSelectedType === 'R'
    ? data.roadAddress
    : data.jibunAddress;
  const extraAddress = data.userSelectedType === 'R' ? roadAddressExtra(data) : '';
  return `${baseAddress || data.address}${extraAddress}`.trim();
}

function roadAddressExtra(data: KakaoPostcodeData) {
  const extraParts: string[] = [];
  if (data.bname && /[동로가]$/.test(data.bname)) {
    extraParts.push(data.bname);
  }
  if (data.buildingName && data.apartment === 'Y') {
    extraParts.push(data.buildingName);
  }
  return extraParts.length > 0 ? ` (${extraParts.join(', ')})` : '';
}

function SummaryRow({ label, value, muted = false, strong = false }: { label: string; value: string; muted?: boolean; strong?: boolean }) {
  return (
    <div className="flex items-center justify-between gap-4 text-sm">
      <span className="font-bold text-slate-500">{label}</span>
      <span className={`${strong ? 'text-lg font-black text-commerce-sale' : 'font-black text-commerce-ink'} ${muted ? 'text-slate-500' : ''}`}>{value}</span>
    </div>
  );
}

function graphHasBlockingFail(graph?: BuildGraphResolveResponse) {
  if (!graph) return false;
  const failedPart = graph.nodes.some((node) => node.type === 'PART' && node.status === 'FAIL');
  const failedEdge = graph.edges.some((edge) => edge.status === 'FAIL');
  const failedTool = graph.toolResults.some((tool) => tool.status === 'FAIL' && ['compatibility', 'power', 'size'].includes(tool.tool));
  return failedPart || failedEdge || failedTool;
}

function todayInputValue() {
  const now = new Date();
  const offset = now.getTimezoneOffset() * 60_000;
  return new Date(now.getTime() - offset).toISOString().slice(0, 10);
}

function defaultPreferredDate() {
  const date = new Date();
  date.setDate(date.getDate() + 3);
  const offset = date.getTimezoneOffset() * 60_000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 10);
}
