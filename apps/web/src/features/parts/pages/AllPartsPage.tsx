import { Link } from 'react-router-dom';
import { Screen } from '../../../components/ui';
import { LegacySelfQuoteListSections } from '../components/slot-board/LegacySelfQuoteListSections';

export function AllPartsPage() {
  return (
    <Screen>
      <div className="mb-5 flex flex-col gap-3 border-b border-commerce-line pb-5 lg:flex-row lg:items-end lg:justify-between">
        <div>
          <div className="text-xs font-black text-[#ce7237]">Parts catalog</div>
          <h1 className="mt-1 text-2xl font-black tracking-tight text-commerce-ink">전체 부품</h1>
          <p className="mt-2 max-w-[72ch] break-keep text-sm leading-6 text-slate-600">
            등록된 부품과 현재가를 확인하고, 필요한 부품은 현재 견적에 바로 담을 수 있습니다.
          </p>
        </div>
        <Link
          to="/self-quote"
          className="inline-flex min-h-11 items-center justify-center rounded-md border border-commerce-line bg-white px-4 py-3 text-sm font-black text-commerce-ink hover:border-commerce-ink"
        >
          셀프 견적 보드로 이동
        </Link>
      </div>
      <LegacySelfQuoteListSections />
    </Screen>
  );
}
