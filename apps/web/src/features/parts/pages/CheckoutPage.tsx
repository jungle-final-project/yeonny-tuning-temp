import { useQuery } from '@tanstack/react-query';
import { ArrowLeft, Bell, CheckCircle2, CreditCard, ExternalLink, PackageCheck, ReceiptText, ShieldCheck, ShoppingBag } from 'lucide-react';
import type React from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Panel, Screen, StateMessage } from '../../../components/ui';
import { partImageUrl, partShortSpec } from '../partDisplay';
import { getCurrentQuoteDraft } from '../partsApi';
import type { QuoteDraft, QuoteDraftItem } from '../types';

const CHECKOUT_SNAPSHOT_KEY = 'buildgraph.checkout.demoSnapshot';

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

type CheckoutSnapshot = {
  orderNo: string;
  draftId?: string | null;
  draftName: string;
  totalPrice: number;
  itemCount: number;
  purchasableCount: number;
  createdAt: string;
  items: Array<{
    partId: string;
    category: string;
    name: string;
    manufacturer?: string | null;
    quantity: number;
    currentPrice: number;
    lineTotal: number;
    supplierName?: string | null;
    offerUrl?: string | null;
    imageUrl?: string | null;
  }>;
};

export function CheckoutPage() {
  const navigate = useNavigate();
  const { data: quoteDraft, isLoading, isError } = useQuery({
    queryKey: ['quote-draft', 'current'],
    queryFn: getCurrentQuoteDraft
  });

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
        <StateMessage type="warn" title="구매 확인 정보 조회 실패" body="현재 견적 장바구니를 불러오지 못했습니다. 잠시 후 다시 시도해주세요." />
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

  const purchasableCount = quoteDraft.items.filter((item) => Boolean(item.externalOffer?.offerUrl)).length;
  const unavailableCount = quoteDraft.items.length - purchasableCount;

  const completeDemoPayment = () => {
    writeCheckoutSnapshot(createCheckoutSnapshot(quoteDraft));
    navigate('/checkout/complete');
  };

  return (
    <Screen>
      <div className="mb-5 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <Link to="/self-quote" className="inline-flex items-center gap-2 text-sm font-black text-brand-blue hover:underline">
            <ArrowLeft size={16} />
            셀프 견적으로 돌아가기
          </Link>
          <h1 className="mt-3 text-3xl font-black tracking-tight text-commerce-ink">구매 전 확인</h1>
          <p className="mt-2 max-w-2xl break-keep text-sm leading-6 text-slate-600">
            현재 견적 장바구니의 부품, 수량, 구매처 링크를 결제 전에 한 번 더 확인합니다. 실제 구매와 결제는 각 부품의 외부 구매처에서 진행합니다.
          </p>
        </div>
        <div className="rounded-lg border border-commerce-line bg-white px-4 py-3 text-sm shadow-sm">
          <div className="font-black text-commerce-ink">{quoteDraft.name}</div>
          <div className="mt-1 text-xs font-bold text-slate-500">현재 장바구니 기준 · 서버 저장 상태 변경 없음</div>
        </div>
      </div>

      <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_380px]">
        <div className="min-w-0 space-y-5">
          <CheckoutReadiness purchasableCount={purchasableCount} unavailableCount={unavailableCount} />
          <Panel title={`주문 부품 ${quoteDraft.itemCount}개`} subtitle="부품별 구매처가 다를 수 있으니 링크 상태를 확인하세요.">
            <div className="space-y-3">
              {quoteDraft.items.map((item) => <CheckoutItemCard key={item.partId} item={item} />)}
            </div>
          </Panel>
          <Panel title="유의사항" subtitle="이번 화면은 프론트 데모 결제 흐름입니다.">
            <div className="grid gap-3 md:grid-cols-3">
              <NoticeItem icon={<ShieldCheck size={18} />} title="서버 주문 저장 없음" body="데모 결제는 quote draft 상태를 ORDERED로 바꾸지 않습니다." />
              <NoticeItem icon={<ExternalLink size={18} />} title="외부 구매처 기준" body="실제 구매 가능 여부, 배송비, 할인은 이동한 구매처에서 확인합니다." />
              <NoticeItem icon={<Bell size={18} />} title="가격 알림 유지" body="목표가 알림은 기존 내 견적함 흐름으로 이어집니다." />
            </div>
          </Panel>
        </div>

        <aside className="min-w-0 xl:sticky xl:top-5 xl:self-start">
          <section className="overflow-hidden rounded-lg border border-commerce-line bg-white shadow-product">
            <div className="border-b border-commerce-line bg-slate-950 px-5 py-4 text-white">
              <div className="flex items-center gap-2 text-sm font-black">
                <ReceiptText size={18} />
                결제 금액
              </div>
              <p className="mt-1 text-xs font-bold text-white/65">데모 결제 후 실제 구매처 링크를 다시 확인할 수 있습니다.</p>
            </div>
            <div className="space-y-4 p-5">
              <SummaryRow label="상품 금액" value={`${quoteDraft.totalPrice.toLocaleString()}원`} />
              <SummaryRow label="구매처 링크" value={`${purchasableCount}/${quoteDraft.items.length}개 가능`} />
              <SummaryRow label="배송비/할인" value="구매처에서 확인" muted />
              <div className="border-t border-commerce-line pt-4">
                <SummaryRow label="최종 견적 금액" value={`${quoteDraft.totalPrice.toLocaleString()}원`} strong />
              </div>
              <div className="rounded-lg border border-blue-100 bg-blue-50 p-3 text-xs font-bold leading-5 text-brand-blue">
                이 버튼은 데모 결제 완료 화면으로 이동하며 실제 결제, 주문 저장, 장바구니 비우기는 발생하지 않습니다.
              </div>
              <button
                type="button"
                onClick={completeDemoPayment}
                aria-label={`${quoteDraft.totalPrice.toLocaleString()}원 데모 결제하기`}
                className="flex min-h-12 w-full items-center justify-center gap-2 rounded-md bg-commerce-ink px-4 py-3 text-sm font-black text-white transition hover:bg-slate-700 focus:outline-none focus:ring-4 focus:ring-blue-100"
              >
                <CreditCard size={17} />
                {quoteDraft.totalPrice.toLocaleString()}원 데모 결제하기
              </button>
              <Link to="/my/quotes" className="flex min-h-11 items-center justify-center gap-2 rounded-md border border-commerce-line bg-white px-4 py-3 text-sm font-black text-commerce-ink hover:border-commerce-ink">
                <Bell size={16} />
                목표가 알림 설정
              </Link>
            </div>
          </section>
        </aside>
      </div>
    </Screen>
  );
}

export function CheckoutCompletePage() {
  const snapshot = readCheckoutSnapshot();

  if (!snapshot) {
    return (
      <Screen>
        <section className="rounded-lg border border-dashed border-commerce-line bg-white p-8 text-center">
          <div className="mx-auto grid h-14 w-14 place-items-center rounded-lg bg-slate-100 text-slate-500">
            <ReceiptText size={24} />
          </div>
          <h1 className="mt-4 text-2xl font-black text-commerce-ink">완료된 데모 결제 정보가 없습니다</h1>
          <p className="mx-auto mt-2 max-w-xl break-keep text-sm leading-6 text-slate-600">
            현재 브라우저 세션에 저장된 checkout snapshot이 없습니다. 셀프 견적에서 부품을 담은 뒤 다시 진행해주세요.
          </p>
          <Link to="/self-quote" className="mt-5 inline-flex min-h-11 items-center justify-center rounded-md bg-commerce-ink px-5 text-sm font-black text-white hover:bg-slate-700">
            셀프 견적으로 돌아가기
          </Link>
        </section>
      </Screen>
    );
  }

  return (
    <Screen>
      <div className="grid gap-5 xl:grid-cols-[minmax(0,1fr)_360px]">
        <section className="overflow-hidden rounded-lg border border-emerald-200 bg-white shadow-product">
          <div className="border-b border-emerald-100 bg-emerald-50 px-5 py-5">
            <div className="inline-flex items-center gap-2 rounded bg-white px-3 py-1 text-xs font-black text-emerald-700">
              <CheckCircle2 size={15} />
              프론트 데모 완료
            </div>
            <h1 className="mt-3 text-3xl font-black text-commerce-ink">데모 결제 완료</h1>
            <p className="mt-2 break-keep text-sm leading-6 text-slate-600">
              주문번호 {snapshot.orderNo} 기준으로 구매 전 확인 내역을 저장했습니다. 실제 결제와 주문은 생성되지 않았습니다.
            </p>
          </div>
          <div className="p-5">
            <div className="mb-4 flex flex-wrap items-center justify-between gap-3 rounded-lg border border-commerce-line bg-slate-50 p-4">
              <div>
                <div className="text-xs font-bold text-slate-500">데모 주문번호</div>
                <div className="mt-1 font-black text-commerce-ink">{snapshot.orderNo}</div>
              </div>
              <div className="text-right">
                <div className="text-xs font-bold text-slate-500">결제 표시 금액</div>
                <div className="mt-1 text-xl font-black text-commerce-sale">{snapshot.totalPrice.toLocaleString()}원</div>
              </div>
            </div>
            <div className="space-y-3">
              {snapshot.items.map((item) => <SnapshotItemCard key={item.partId} item={item} />)}
            </div>
          </div>
        </section>

        <aside className="min-w-0 xl:sticky xl:top-5 xl:self-start">
          <section className="rounded-lg border border-commerce-line bg-white p-5 shadow-product">
            <h2 className="text-lg font-black text-commerce-ink">다음 행동</h2>
            <p className="mt-2 break-keep text-sm leading-6 text-slate-600">구매처 링크를 열어 실제 결제 조건을 확인하거나 목표가 알림으로 이어갈 수 있습니다.</p>
            <div className="mt-5 space-y-2">
              <Link to="/checkout" className="flex min-h-11 items-center justify-center gap-2 rounded-md bg-commerce-ink px-4 py-3 text-sm font-black text-white hover:bg-slate-700">
                <ShoppingBag size={16} />
                구매처 링크 다시 확인
              </Link>
              <Link to="/self-quote" className="flex min-h-11 items-center justify-center gap-2 rounded-md border border-commerce-line bg-white px-4 py-3 text-sm font-black text-commerce-ink hover:border-commerce-ink">
                <PackageCheck size={16} />
                셀프 견적으로 돌아가기
              </Link>
              <Link to="/my/quotes" className="flex min-h-11 items-center justify-center gap-2 rounded-md border border-commerce-line bg-white px-4 py-3 text-sm font-black text-commerce-ink hover:border-commerce-ink">
                <Bell size={16} />
                목표가 알림 설정
              </Link>
            </div>
          </section>
        </aside>
      </div>
    </Screen>
  );
}

function EmptyCheckout() {
  return (
    <section className="rounded-lg border border-dashed border-commerce-line bg-white p-8 text-center">
      <div className="mx-auto grid h-14 w-14 place-items-center rounded-lg bg-slate-100 text-slate-500">
        <ShoppingBag size={24} />
      </div>
      <h1 className="mt-4 text-2xl font-black text-commerce-ink">구매할 부품이 없습니다</h1>
      <p className="mx-auto mt-2 max-w-xl break-keep text-sm leading-6 text-slate-600">
        셀프 견적에서 부품을 담으면 이 화면에서 구매 전 확인과 부품별 구매처 링크를 볼 수 있습니다.
      </p>
      <Link to="/self-quote" className="mt-5 inline-flex min-h-11 items-center justify-center rounded-md bg-commerce-ink px-5 text-sm font-black text-white hover:bg-slate-700">
        셀프 견적으로 돌아가기
      </Link>
    </section>
  );
}

function CheckoutReadiness({ purchasableCount, unavailableCount }: { purchasableCount: number; unavailableCount: number }) {
  return (
    <section className="rounded-lg border border-commerce-line bg-white p-5 shadow-sm">
      <div className="flex flex-col gap-4 md:flex-row md:items-center md:justify-between">
        <div>
          <h2 className="text-xl font-black text-commerce-ink">구매 준비 상태</h2>
          <p className="mt-2 break-keep text-sm leading-6 text-slate-600">
            장바구니 조합은 유지한 채, 외부 구매처로 이동하기 전 링크 상태와 부품 구성을 확인합니다.
          </p>
        </div>
        <div className="grid grid-cols-2 gap-2 text-center text-xs font-black">
          <div className="rounded-lg border border-emerald-100 bg-emerald-50 px-4 py-3 text-emerald-700">
            <div className="text-xl">{purchasableCount}</div>
            <div>구매처 링크</div>
          </div>
          <div className="rounded-lg border border-orange-100 bg-orange-50 px-4 py-3 text-orange-700">
            <div className="text-xl">{unavailableCount}</div>
            <div>정보 없음</div>
          </div>
        </div>
      </div>
    </section>
  );
}

function CheckoutItemCard({ item }: { item: QuoteDraftItem }) {
  const offerUrl = item.externalOffer?.offerUrl;
  const supplierName = item.externalOffer?.supplierName ?? '저장된 구매처 없음';
  return (
    <article className="grid gap-3 rounded-lg border border-commerce-line bg-white p-3 sm:grid-cols-[88px_minmax(0,1fr)]">
      <img src={partImageUrl(item)} alt={`${item.name} 제품 사진`} className="h-24 w-full rounded-md border border-commerce-line bg-slate-50 object-contain sm:w-24" />
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
            <div className="text-[11px] font-black uppercase text-slate-400">supplier</div>
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

function SnapshotItemCard({ item }: { item: CheckoutSnapshot['items'][number] }) {
  return (
    <article className="rounded-lg border border-commerce-line bg-white p-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <span className="rounded bg-slate-100 px-2 py-1 text-[11px] font-black text-slate-700">{CATEGORY_LABELS[item.category] ?? item.category}</span>
            <span className="text-xs font-bold text-slate-500">{item.manufacturer ?? '제조사 미확인'} · 수량 {item.quantity}</span>
          </div>
          <h3 className="mt-2 break-keep font-black text-commerce-ink">{item.name}</h3>
          <div className="mt-1 text-xs font-bold text-slate-500">{item.supplierName ?? '저장된 구매처 없음'}</div>
        </div>
        <div className="flex items-center justify-between gap-3 sm:flex-col sm:items-end">
          <span className="font-black text-commerce-sale">{item.lineTotal.toLocaleString()}원</span>
          {item.offerUrl ? (
            <a href={item.offerUrl} target="_blank" rel="noreferrer" className="inline-flex min-h-9 items-center justify-center rounded-md border border-commerce-line px-3 text-xs font-black text-brand-blue hover:border-brand-blue">
              구매처 이동
            </a>
          ) : (
            <span className="rounded bg-slate-100 px-2 py-1 text-[11px] font-black text-slate-400">구매처 정보 없음</span>
          )}
        </div>
      </div>
    </article>
  );
}

function NoticeItem({ icon, title, body }: { icon: React.ReactNode; title: string; body: string }) {
  return (
    <div className="rounded-lg border border-commerce-line bg-slate-50 p-4">
      <div className="flex items-center gap-2 font-black text-commerce-ink">
        <span className="text-brand-blue">{icon}</span>
        {title}
      </div>
      <p className="mt-2 break-keep text-xs font-bold leading-5 text-slate-500">{body}</p>
    </div>
  );
}

function SummaryRow({ label, value, muted = false, strong = false }: { label: string; value: string; muted?: boolean; strong?: boolean }) {
  return (
    <div className="flex items-center justify-between gap-4 text-sm">
      <span className="font-bold text-slate-500">{label}</span>
      <span className={`${strong ? 'text-lg font-black text-commerce-sale' : 'font-black text-commerce-ink'} ${muted ? 'text-slate-500' : ''}`}>{value}</span>
    </div>
  );
}

function createCheckoutSnapshot(quoteDraft: QuoteDraft): CheckoutSnapshot {
  return {
    orderNo: createDemoOrderNo(),
    draftId: quoteDraft.id,
    draftName: quoteDraft.name,
    totalPrice: quoteDraft.totalPrice,
    itemCount: quoteDraft.itemCount,
    purchasableCount: quoteDraft.items.filter((item) => Boolean(item.externalOffer?.offerUrl)).length,
    createdAt: new Date().toISOString(),
    items: quoteDraft.items.map((item) => ({
      partId: item.partId,
      category: item.category,
      name: item.name,
      manufacturer: item.manufacturer,
      quantity: item.quantity,
      currentPrice: item.currentPrice,
      lineTotal: item.lineTotal,
      supplierName: item.externalOffer?.supplierName,
      offerUrl: item.externalOffer?.offerUrl,
      imageUrl: item.externalOffer?.imageUrl
    }))
  };
}

function createDemoOrderNo() {
  const now = new Date();
  const datePart = `${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, '0')}${String(now.getDate()).padStart(2, '0')}`;
  const suffix = Math.random().toString(36).slice(2, 8).toUpperCase();
  return `BG-${datePart}-${suffix}`;
}

function writeCheckoutSnapshot(snapshot: CheckoutSnapshot) {
  window.sessionStorage.setItem(CHECKOUT_SNAPSHOT_KEY, JSON.stringify(snapshot));
}

function readCheckoutSnapshot(): CheckoutSnapshot | null {
  try {
    const raw = window.sessionStorage.getItem(CHECKOUT_SNAPSHOT_KEY);
    return raw ? JSON.parse(raw) as CheckoutSnapshot : null;
  } catch {
    return null;
  }
}
