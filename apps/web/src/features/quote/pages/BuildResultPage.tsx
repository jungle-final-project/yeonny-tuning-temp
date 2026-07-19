import { useMutation, useQuery } from '@tanstack/react-query';
import { Link, Navigate, useNavigate, useParams } from 'react-router-dom';
import { Panel, Screen, StateMessage } from '../../../components/ui';
import { BuildDetailSections, latestUserMessage, temporaryBuildToBuildSummary } from '../components/BuildDetailSections';
import { QuoteCard } from '../components/QuoteCard';
import { buildSaveErrorMessage, getBuild, resolveBuildGraph, saveBuildFromChat } from '../quoteApi';
import { markAssistantBuildSaved, readAssistantSession, type AiRecommendedBuild, type PartCategory } from '../aiSelection';
import type { BuildItem, BuildSummary } from '../types';

export function BuildResultPage() {
  const { buildId = '00000000-0000-4000-8000-000000002001' } = useParams();
  const navigate = useNavigate();
  const assistantSession = readAssistantSession();
  const savedBuildId = assistantSession.savedBuildIds[buildId];
  const temporaryBuild = savedBuildId ? undefined : assistantSession.latestBuilds.find((build) => build.id === buildId);
  const { data: build, isLoading, isError } = useQuery({
    queryKey: ['build', buildId],
    queryFn: () => getBuild(buildId),
    enabled: !savedBuildId && !temporaryBuild
  });
  const displayBuild = temporaryBuild ? temporaryBuildToBuildSummary(temporaryBuild) : build;
  const graphItems = buildGraphItems(displayBuild);
  const graphQuery = useQuery({
    queryKey: ['build-graph', 'build-detail', displayBuild?.id, buildGraphSignature(graphItems), displayBuild?.totalPrice],
    queryFn: () => resolveBuildGraph({
      source: 'AI_BUILD',
      view: 'FULL',
      items: graphItems,
      budgetWon: displayBuild?.totalPrice
    }),
    enabled: Boolean(!savedBuildId && displayBuild && graphItems.length > 0)
  });
  const saveMutation = useMutation({
    mutationFn: (sourceBuild: AiRecommendedBuild) => saveBuildFromChat({
      sourceBuildId: sourceBuild.id,
      lastUserMessage: latestUserMessage(assistantSession),
      build: sourceBuild
    }),
    onSuccess: (response, sourceBuild) => {
      markAssistantBuildSaved(sourceBuild.id, response.id);
      navigate(`/builds/${response.id}`, { replace: true });
    }
  });

  if (savedBuildId) {
    return <Navigate to={`/builds/${savedBuildId}`} replace />;
  }

  const isTemporaryBuild = Boolean(temporaryBuild);

  if (!temporaryBuild && isLoading) {
    return (
      <Screen>
        <Panel title="추천 견적 결과">
          <StateMessage type="info" title="견적 로딩 중" body="추천 견적 상세와 검증 결과를 불러오고 있습니다." />
        </Panel>
      </Screen>
    );
  }

  if (!displayBuild || (!temporaryBuild && isError)) {
    return (
      <Screen>
        <Panel title="추천 견적 결과">
          <StateMessage type="warn" title="견적 조회 실패" body="선택한 추천 견적을 불러오지 못했습니다." />
        </Panel>
      </Screen>
    );
  }

  return (
    <Screen>
      <div className="space-y-5">
        <Panel title={`추천 견적 결과 / ${displayBuild.name}`} subtitle={isTemporaryBuild ? `임시 추천 ID ${displayBuild.id.slice(0, 8)}` : `견적 ID ${displayBuild.id.slice(0, 8)}`}>
          {isTemporaryBuild ? (
            <div className="mb-3 rounded-md border border-[#f4c8b2] bg-[#fff5ef] px-4 py-3 text-sm font-bold text-[#de6c2d]">
              저장 전 AI 챗봇 추천
            </div>
          ) : null}
          <div className="flex gap-4 overflow-x-auto pb-1">
            <QuoteCard build={displayBuild} selected showActions={!isTemporaryBuild} />
          </div>
        </Panel>
        <BuildDetailSections
          displayBuild={displayBuild}
          compatibilityGraph={graphQuery.data}
          compatibilityPending={graphQuery.isFetching}
          conditionBody={isTemporaryBuild ? '저장 버튼을 누르면 서버에서 다시 검증한 뒤 견적으로 저장합니다.' : '현재 구성은 저장된 내부 자산 기준 자동 검증을 통과했습니다.'}
          summaryActions={isTemporaryBuild && temporaryBuild ? (
            <>
              <button
                type="button"
                onClick={() => saveMutation.mutate(temporaryBuild)}
                disabled={saveMutation.isPending}
                className="block w-full rounded bg-[#de6c2d] px-4 py-3 text-center text-sm font-bold text-white hover:bg-[#c45c22] disabled:cursor-wait disabled:bg-slate-400 disabled:hover:bg-slate-400"
              >
                {saveMutation.isPending ? '저장 중' : '견적 저장'}
              </button>
              {saveMutation.isError ? (
                <StateMessage type="warn" title="견적 저장 실패" body={buildSaveErrorMessage(saveMutation.error)} />
              ) : null}
            </>
          ) : (
            <>
              <Link to="/my/quotes" className="block rounded bg-[#de6c2d] px-4 py-3 text-center text-sm font-bold text-white hover:bg-[#c45c22]">내 견적함 보기</Link>
              <Link to={`/builds/${displayBuild.id}/change-part`} className="block rounded border border-slate-300 px-4 py-3 text-center text-sm font-bold hover:border-commerce-ink">부품 변경 비교</Link>
            </>
          )}
        />
      </div>
    </Screen>
  );
}

const BUILD_GRAPH_CATEGORIES = new Set<PartCategory>(['CPU', 'MOTHERBOARD', 'RAM', 'GPU', 'STORAGE', 'PSU', 'CASE', 'COOLER']);

function buildGraphItems(build?: BuildSummary) {
  return (build?.items ?? []).flatMap((item) => {
    if (!item.partId || !isPartCategory(item.category)) {
      return [];
    }
    return [{
      partId: item.partId,
      category: item.category,
      quantity: buildItemQuantity(item)
    }];
  });
}

function isPartCategory(category: string): category is PartCategory {
  return BUILD_GRAPH_CATEGORIES.has(category as PartCategory);
}

function buildItemQuantity(item: BuildItem) {
  const directQuantity = (item as BuildItem & { quantity?: unknown }).quantity;
  const attributeQuantity = item.attributes?.quantity;
  const quantity = typeof directQuantity === 'number' ? directQuantity : attributeQuantity;
  return typeof quantity === 'number' && Number.isFinite(quantity) && quantity >= 1 ? Math.floor(quantity) : 1;
}

function buildGraphSignature(items: Array<{ partId: string; category: PartCategory; quantity: number }>) {
  return items
    .map((item) => `${item.category}:${item.partId}:${item.quantity}`)
    .sort()
    .join('|');
}
