import { FolderPlus, MessageCircle, ShoppingCart } from 'lucide-react';
import { Link } from 'react-router-dom';
import { StateMessage } from '../../../../components/ui';
import type { QuoteDraft } from '../../types';
import { SLOT_COUNT, SLOT_CONFIGS } from './slotBoardConfig';

type SlotStatusBarProps = {
  quoteDraft: QuoteDraft | undefined;
  hasToken: boolean;
  loginHref: string;
  isDraftLoading: boolean;
  isDraftError: boolean;
  /** 현재 견적 검증에 FAIL이 있으면 구매만 차단한다. 저장은 허용한다. */
  hasCompatibilityFail: boolean;
  onSave: () => void;
  isSavePending: boolean;
  isSaveSuccess: boolean;
  isSaveError: boolean;
};

export function SlotStatusBar({
  quoteDraft,
  hasToken,
  loginHref,
  isDraftLoading,
  isDraftError,
  hasCompatibilityFail,
  onSave,
  isSavePending,
  isSaveSuccess,
  isSaveError
}: SlotStatusBarProps) {
  const items = quoteDraft?.items ?? [];
  const totalPrice = quoteDraft?.totalPrice ?? 0;
  const filledCount = SLOT_CONFIGS.filter((slot) => items.some((item) => item.category === slot.category)).length;
  const emptyCount = SLOT_COUNT - filledCount;
  const hasItems = items.length > 0;

  return (
    <section data-testid="slot-status-bar" className="space-y-3">
      {/* 상태 알림 배너 */}
      {hasItems && hasCompatibilityFail ? (
        <div className="rounded-lg border border-red-200 bg-red-50 px-4 py-2.5 text-xs font-black text-red-700">
          안 맞는 부품이 있어 구매할 수 없습니다. 문제 슬롯을 교체해 주세요.
        </div>
      ) : !hasToken ? (
        <div className="rounded-lg border border-dashed border-slate-300 px-4 py-2.5 text-xs font-bold text-slate-500">
          로그인하면 슬롯에 담은 부품이 서버 견적초안에 저장됩니다.
          <Link to={loginHref} className="ml-2 font-black text-brand-blue hover:underline">로그인하고 견적 담기</Link>
        </div>
      ) : isDraftLoading ? (
        <div className="rounded-lg border border-commerce-line px-4 py-2.5 text-xs font-bold text-slate-500">내 견적초안을 불러오는 중입니다.</div>
      ) : isDraftError ? (
        <div className="rounded-lg border border-orange-200 bg-orange-50 px-4 py-2.5 text-xs font-bold text-orange-700">견적초안 API를 불러오지 못했습니다.</div>
      ) : emptyCount > 0 ? (
        <div className="rounded-lg border border-blue-100 bg-blue-50/60 px-4 py-2.5 text-xs font-black text-brand-blue">
          미장착 슬롯 {emptyCount}개가 있습니다
        </div>
      ) : null}

      {/* CTA 행 */}
      <div className="panel flex flex-wrap items-center justify-between gap-3 px-4 py-3">
        <div>
          <div className="text-[11px] font-bold text-slate-500">견적 합계</div>
          <div className="text-xl font-black tracking-tight text-brand-blue">{totalPrice.toLocaleString()}원</div>
          <div className="text-[11px] font-bold text-slate-400">장착 {filledCount}/{SLOT_COUNT}</div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Link
            to="/chatbot"
            className="inline-flex min-h-10 items-center gap-1.5 rounded-md border border-commerce-line bg-white px-4 text-sm font-black text-slate-700 hover:border-commerce-ink"
          >
            <MessageCircle size={15} className="text-brand-blue" />
            AI 상담
          </Link>
          {hasItems ? (
            <button
              type="button"
              onClick={onSave}
              disabled={isSavePending}
              className="inline-flex min-h-10 items-center gap-1.5 rounded-md border border-slate-300 bg-white px-4 text-sm font-black text-slate-700 hover:border-commerce-ink disabled:cursor-wait disabled:opacity-60"
            >
              <FolderPlus size={15} />
              {isSavePending ? '추가 중' : '내 견적함에 추가'}
            </button>
          ) : null}
          {hasItems && !hasCompatibilityFail ? (
            <Link
              to="/checkout"
              className="inline-flex min-h-10 items-center gap-2 rounded-md bg-brand-blue px-5 text-sm font-black text-white hover:bg-blue-700"
            >
              <ShoppingCart size={16} />
              구매하기
            </Link>
          ) : (
            <button
              type="button"
              disabled
              className="inline-flex min-h-10 cursor-not-allowed items-center gap-2 rounded-md bg-slate-200 px-5 text-sm font-black text-slate-400"
            >
              <ShoppingCart size={16} />
              구매하기
            </button>
          )}
        </div>
      </div>

      {isSaveSuccess ? (
        <div className="space-y-2">
          <StateMessage type="success" title="내 견적함에 추가했습니다." body="저장된 견적은 내 견적함 / 목표가 알림 화면에서 다시 확인할 수 있습니다." />
          <Link to="/my/quotes" className="flex min-h-10 items-center justify-center rounded-md border border-emerald-200 bg-emerald-50 px-3 text-xs font-black text-emerald-700 hover:border-emerald-300">
            내 견적함 보기
          </Link>
        </div>
      ) : null}
      {isSaveError ? (
        <StateMessage type="warn" title="내 견적함 추가 실패" body="현재 견적을 저장하지 못했습니다. 잠시 후 다시 시도해 주세요." />
      ) : null}
    </section>
  );
}
