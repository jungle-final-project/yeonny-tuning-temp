import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AdminShell, DataTable, Panel, StateMessage, StatusBadge } from '../../../components/ui';
import {
  approvePartCatalogCandidate,
  createAiAssetDraftFromManufacturerPost,
  createCandidateFromManufacturerPost,
  createAdminPart,
  createPartAliasRule,
  createManufacturerPost,
  createManufacturerSource,
  deleteManufacturerPost,
  deleteManufacturerSource,
  deletePartCatalogCandidate,
  deleteAdminPart,
  getAdminPart,
  getAdminPartsQualityReport,
  getPartAliasReviewSummary,
  listAdminParts,
  listPartAliasReviewItems,
  listPartAliasRules,
  listManufacturerPosts,
  listManufacturerReleaseCandidates,
  listManufacturerSources,
  refreshPartCatalogCandidateOffers,
  rejectPartCatalogCandidate,
  ignorePartAliasReviewItem,
  resolvePartAliasReviewItem,
  restoreAdminPart,
  restoreManufacturerPost,
  restoreManufacturerSource,
  restorePartCatalogCandidate,
  scanAllManufacturerSources,
  scanManufacturerSource,
  updateAdminPart,
  updateAdminPartExternalOffer,
  updateAdminPartManualPrice,
  updateManufacturerPost,
  updateManufacturerSource,
  updatePartCatalogCandidate,
  type AdminExternalOfferPayload,
  type AdminPart,
  type AdminPartPayload,
  type CandidatePayload,
  type ManufacturerPost,
  type ManufacturerPostPayload,
  type ManufacturerSource,
  type ManufacturerSourcePayload,
  type PartAliasReviewItem,
  type PartAliasRule,
  type PartCatalogCandidate,
  type PartQualityReportActionItem,
  type PartQualityReportCategory
} from '../adminApi';

const CATEGORIES = ['CPU', 'MOTHERBOARD', 'RAM', 'GPU', 'STORAGE', 'PSU', 'CASE', 'COOLER'];
const STATUSES = ['ACTIVE', 'INACTIVE', 'DISCONTINUED', 'DELETED'];
const TABS = ['기본 정보', '스펙', '가격/Offer', '이력'] as const;
const INTAKE_TABS = ['추적 Source', '감지 게시글', '신제품 후보함', '자산 품질 점검', 'Alias/스펙 검수 큐'] as const;
const SOURCE_TYPES = ['NEWS', 'PRODUCT_RELEASE', 'SUPPORT_NEWS', 'RSS', 'SITEMAP'];
const SOURCE_STATUSES = ['ACTIVE', 'PAUSED', 'ERROR'];
const POST_STATUSES = ['PENDING', 'PRODUCT_CANDIDATE', 'IGNORED', 'FAILED'];
const ALIAS_REVIEW_STATUSES = ['OPEN', 'RESOLVED', 'IGNORED'];

type AdminPartsFilter = {
  category: string;
  status: string;
  q: string;
  manufacturer: string;
  minPrice: string;
  maxPrice: string;
  includeDeleted: boolean;
  sort: string;
};

type PartDraft = {
  category: string;
  name: string;
  manufacturer: string;
  price: string;
  status: string;
  attributes: Record<string, unknown>;
};

type OfferDraft = {
  searchQuery: string;
  title: string;
  imageUrl: string;
  supplierName: string;
  offerUrl: string;
  lowPrice: string;
};

type SourceDraft = {
  manufacturer: string;
  categoryScope: string;
  sourceType: string;
  sourceUrl: string;
  enabled: boolean;
  pollIntervalMinutes: string;
  status: string;
  parserConfigText: string;
};

type PostDraft = {
  sourceId: string;
  externalUrl: string;
  title: string;
  publishedAt: string;
  excerpt: string;
  classificationStatus: string;
  detectedCategory: string;
  detectedProductName: string;
  confidence: string;
};

type CandidateDraft = {
  category: string;
  searchQuery: string;
  title: string;
  manufacturerGuess: string;
  imageUrl: string;
  supplierName: string;
  offerUrl: string;
  lowPrice: string;
};

type AliasReviewDraft = {
  aliasText: string;
  category: string;
  targetField: string;
  canonicalValue: string;
  note: string;
};

type AliasReviewFilters = {
  status: string;
  category: string;
  targetField: string;
  sourceType: string;
};

type SpecField = {
  key: string;
  label: string;
  type: 'text' | 'number' | 'boolean' | 'list';
};

const DEFAULT_FILTER: AdminPartsFilter = {
  category: '',
  status: 'ACTIVE',
  q: '',
  manufacturer: '',
  minPrice: '',
  maxPrice: '',
  includeDeleted: false,
  sort: 'category'
};

const DEFAULT_DRAFT: PartDraft = {
  category: 'GPU',
  name: '',
  manufacturer: '',
  price: '0',
  status: 'INACTIVE',
  attributes: {}
};

const DEFAULT_OFFER: OfferDraft = {
  searchQuery: '',
  title: '',
  imageUrl: '',
  supplierName: '',
  offerUrl: '',
  lowPrice: ''
};

const DEFAULT_SOURCE_DRAFT: SourceDraft = {
  manufacturer: '',
  categoryScope: 'ALL',
  sourceType: 'NEWS',
  sourceUrl: '',
  enabled: true,
  pollIntervalMinutes: '1440',
  status: 'ACTIVE',
  parserConfigText: '{\n  "sourceRole": "OFFICIAL_MANUFACTURER_NEWS"\n}'
};

const DEFAULT_POST_DRAFT: PostDraft = {
  sourceId: '',
  externalUrl: '',
  title: '',
  publishedAt: '',
  excerpt: '',
  classificationStatus: 'PENDING',
  detectedCategory: '',
  detectedProductName: '',
  confidence: ''
};

const DEFAULT_CANDIDATE_DRAFT: CandidateDraft = {
  category: 'GPU',
  searchQuery: '',
  title: '',
  manufacturerGuess: '',
  imageUrl: '',
  supplierName: '',
  offerUrl: '',
  lowPrice: ''
};

const DEFAULT_ALIAS_REVIEW_DRAFT: AliasReviewDraft = {
  aliasText: '',
  category: 'GPU',
  targetField: 'rank',
  canonicalValue: '',
  note: ''
};

const DEFAULT_ALIAS_REVIEW_FILTERS: AliasReviewFilters = {
  status: 'OPEN',
  category: '',
  targetField: '',
  sourceType: ''
};

const COMMON_SPEC_FIELDS: SpecField[] = [
  { key: 'shortSpec', label: '짧은 스펙', type: 'text' },
  { key: 'specReferenceUrl', label: '공식 스펙 URL', type: 'text' }
];

const CATEGORY_SPEC_FIELDS: Record<string, SpecField[]> = {
  CPU: [
    { key: 'socket', label: '소켓', type: 'text' },
    { key: 'architecture', label: '아키텍처', type: 'text' },
    { key: 'coreCount', label: '코어 수', type: 'number' },
    { key: 'threadCount', label: '스레드 수', type: 'number' },
    { key: 'tdpW', label: 'TDP(W)', type: 'number' },
    { key: 'cpuClass', label: 'CPU 등급', type: 'text' }
  ],
  GPU: [
    { key: 'gpuClass', label: 'GPU 클래스', type: 'text' },
    { key: 'series', label: '시리즈', type: 'text' },
    { key: 'vramGb', label: 'VRAM(GB)', type: 'number' },
    { key: 'memoryType', label: '메모리 타입', type: 'text' },
    { key: 'lengthMm', label: '길이(mm)', type: 'number' },
    { key: 'widthMm', label: '너비(mm)', type: 'number' },
    { key: 'heightMm', label: '높이(mm)', type: 'number' },
    { key: 'slotWidth', label: '슬롯 두께', type: 'text' },
    { key: 'wattage', label: '소비전력(W)', type: 'number' },
    { key: 'requiredSystemPowerW', label: '권장 정격 파워(W)', type: 'number' },
    { key: 'powerConnector', label: '전원 커넥터', type: 'text' }
  ],
  MOTHERBOARD: [
    { key: 'socket', label: '소켓', type: 'text' },
    { key: 'chipset', label: '칩셋', type: 'text' },
    { key: 'memoryType', label: '메모리 타입', type: 'text' },
    { key: 'formFactor', label: '폼팩터', type: 'text' },
    { key: 'widthMm', label: '너비(mm)', type: 'number' },
    { key: 'depthMm', label: '깊이(mm)', type: 'number' },
    { key: 'hasWifi', label: 'Wi-Fi', type: 'boolean' },
    { key: 'pcieGeneration', label: 'PCIe 세대', type: 'text' }
  ],
  RAM: [
    { key: 'memoryType', label: '메모리 타입', type: 'text' },
    { key: 'capacityGb', label: '용량(GB)', type: 'number' },
    { key: 'moduleCount', label: '모듈 수', type: 'number' },
    { key: 'speedMhz', label: '속도(MHz)', type: 'number' },
    { key: 'formFactor', label: '폼팩터', type: 'text' },
    { key: 'xmp', label: 'XMP', type: 'boolean' },
    { key: 'expo', label: 'EXPO', type: 'boolean' },
    { key: 'ecc', label: 'ECC', type: 'boolean' },
    { key: 'registered', label: 'Registered', type: 'boolean' }
  ],
  STORAGE: [
    { key: 'capacityGb', label: '용량(GB)', type: 'number' },
    { key: 'interface', label: '인터페이스', type: 'text' },
    { key: 'generation', label: '세대', type: 'text' },
    { key: 'formFactor', label: '폼팩터', type: 'text' },
    { key: 'readMbps', label: '읽기(MB/s)', type: 'number' },
    { key: 'writeMbps', label: '쓰기(MB/s)', type: 'number' }
  ],
  PSU: [
    { key: 'capacityW', label: '정격 출력(W)', type: 'number' },
    { key: 'wattage', label: '표시 출력(W)', type: 'number' },
    { key: 'efficiency', label: '효율 등급', type: 'text' },
    { key: 'atxSpec', label: 'ATX 규격', type: 'text' },
    { key: 'pcieSpec', label: 'PCIe 규격', type: 'text' },
    { key: 'gpuConnector', label: 'GPU 커넥터', type: 'text' },
    { key: 'modular', label: '모듈러', type: 'boolean' },
    { key: 'widthMm', label: '너비(mm)', type: 'number' },
    { key: 'heightMm', label: '높이(mm)', type: 'number' },
    { key: 'depthMm', label: '깊이(mm)', type: 'number' }
  ],
  CASE: [
    { key: 'formFactor', label: '폼팩터', type: 'text' },
    { key: 'maxGpuLengthMm', label: 'GPU 최대 길이(mm)', type: 'number' },
    { key: 'maxCpuCoolerHeightMm', label: 'CPU 쿨러 최대 높이(mm)', type: 'number' },
    { key: 'maxPsuLengthMm', label: 'PSU 최대 길이(mm)', type: 'number' },
    { key: 'radiatorSupportMm', label: '라디에이터 지원(mm)', type: 'list' },
    { key: 'widthMm', label: '너비(mm)', type: 'number' },
    { key: 'heightMm', label: '높이(mm)', type: 'number' },
    { key: 'depthMm', label: '깊이(mm)', type: 'number' },
    { key: 'frontMesh', label: '전면 메쉬', type: 'boolean' },
    { key: 'airflowFocus', label: '에어플로우 중점', type: 'boolean' }
  ],
  COOLER: [
    { key: 'coolerType', label: '쿨러 타입', type: 'text' },
    { key: 'socketSupport', label: '지원 소켓', type: 'list' },
    { key: 'tdpW', label: '지원 TDP(W)', type: 'number' },
    { key: 'heightMm', label: '높이(mm)', type: 'number' },
    { key: 'radiatorLengthMm', label: '라디에이터 길이(mm)', type: 'number' },
    { key: 'radiatorWidthMm', label: '라디에이터 너비(mm)', type: 'number' },
    { key: 'radiatorThicknessMm', label: '라디에이터 두께(mm)', type: 'number' },
    { key: 'widthMm', label: '너비(mm)', type: 'number' },
    { key: 'depthMm', label: '깊이(mm)', type: 'number' }
  ]
};

export function AdminPartsPage() {
  const queryClient = useQueryClient();
  const [filters, setFilters] = useState<AdminPartsFilter>(DEFAULT_FILTER);
  const [selectedPartId, setSelectedPartId] = useState<string | null>(null);
  const [draft, setDraft] = useState<PartDraft>(DEFAULT_DRAFT);
  const [offerDraft, setOfferDraft] = useState<OfferDraft>(DEFAULT_OFFER);
  const [manualPrice, setManualPrice] = useState('');
  const [manualPriceReason, setManualPriceReason] = useState('');
  const [activeTab, setActiveTab] = useState<(typeof TABS)[number]>('기본 정보');
  const [intakeTab, setIntakeTab] = useState<(typeof INTAKE_TABS)[number]>('추적 Source');
  const [partsListOpen, setPartsListOpen] = useState(true);
  const [manufacturerIntakeOpen, setManufacturerIntakeOpen] = useState(false);
  const [selectedSourceId, setSelectedSourceId] = useState<string | null>(null);
  const [sourceDraft, setSourceDraft] = useState<SourceDraft>(DEFAULT_SOURCE_DRAFT);
  const [selectedPostId, setSelectedPostId] = useState<string | null>(null);
  const [postDraft, setPostDraft] = useState<PostDraft>(DEFAULT_POST_DRAFT);
  const [selectedCandidateId, setSelectedCandidateId] = useState<string | null>(null);
  const [candidateDraft, setCandidateDraft] = useState<CandidateDraft>(DEFAULT_CANDIDATE_DRAFT);
  const [selectedAliasReviewId, setSelectedAliasReviewId] = useState<string | null>(null);
  const [aliasReviewDraft, setAliasReviewDraft] = useState<AliasReviewDraft>(DEFAULT_ALIAS_REVIEW_DRAFT);
  const [aliasReviewFilters, setAliasReviewFilters] = useState<AliasReviewFilters>(DEFAULT_ALIAS_REVIEW_FILTERS);

  const adminPartsQuery = useQuery({
    queryKey: ['admin-parts', filters],
    queryFn: () => listAdminParts({
      category: filters.category || undefined,
      status: filters.status || undefined,
      q: filters.q || undefined,
      manufacturer: filters.manufacturer || undefined,
      minPrice: numberOrUndefined(filters.minPrice),
      maxPrice: numberOrUndefined(filters.maxPrice),
      includeDeleted: filters.includeDeleted,
      page: 0,
      size: 100,
      sort: filters.sort
    })
  });
  const selectedPartQuery = useQuery({
    queryKey: ['admin-part-detail', selectedPartId],
    queryFn: () => getAdminPart(selectedPartId as string),
    enabled: Boolean(selectedPartId)
  });
  const sourcesQuery = useQuery({
    queryKey: ['admin-manufacturer-sources', true],
    queryFn: () => listManufacturerSources(true)
  });
  const postsQuery = useQuery({
    queryKey: ['admin-manufacturer-posts'],
    queryFn: listManufacturerPosts
  });
  const candidatesQuery = useQuery({
    queryKey: ['admin-manufacturer-release-candidates'],
    queryFn: listManufacturerReleaseCandidates
  });
  const qualityReportQuery = useQuery({
    queryKey: ['admin-parts-quality-report'],
    queryFn: getAdminPartsQualityReport
  });
  const aliasReviewQuery = useQuery({
    queryKey: ['admin-part-alias-review-items', aliasReviewFilters],
    queryFn: () => listPartAliasReviewItems({
      status: aliasReviewFilters.status || undefined,
      category: aliasReviewFilters.category || undefined,
      targetField: aliasReviewFilters.targetField || undefined,
      sourceType: aliasReviewFilters.sourceType || undefined,
      page: 0,
      size: 20
    })
  });
  const aliasReviewSummaryQuery = useQuery({
    queryKey: ['admin-part-alias-review-summary'],
    queryFn: getPartAliasReviewSummary
  });
  const aliasRulesQuery = useQuery({
    queryKey: ['admin-part-alias-rules'],
    queryFn: listPartAliasRules
  });

  const parts = adminPartsQuery.data?.items ?? [];
  const selectedPart = selectedPartQuery.data;
  const sources = sourcesQuery.data?.items ?? [];
  const posts = postsQuery.data?.items ?? [];
  const candidates = candidatesQuery.data?.items ?? [];
  const qualityReport = qualityReportQuery.data;
  const aliasReviewItems = aliasReviewQuery.data?.items ?? [];
  const aliasReviewSummary = aliasReviewSummaryQuery.data?.items ?? [];
  const aliasRules = aliasRulesQuery.data?.items ?? [];
  const selectedSource = sources.find((source) => source.id === selectedSourceId);
  const selectedPost = posts.find((post) => post.id === selectedPostId);
  const selectedCandidate = candidates.find((candidate) => candidate.id === selectedCandidateId);
  const selectedAliasReviewItem = aliasReviewItems.find((item) => item.id === selectedAliasReviewId);
  const selectedPostReadyForCandidate = Boolean(
    selectedPost
      && selectedPost.classificationStatus === 'PRODUCT_CANDIDATE'
      && selectedPost.detectedCategory
      && selectedPost.detectedProductName
      && !selectedPost.catalogCandidateId
  );
  const selectedCandidateCanApprove = Boolean(
    selectedCandidate
      && selectedCandidate.candidateStatus === 'DISCOVERED'
      && !selectedCandidate.publishedPartId
  );

  useEffect(() => {
    if (!selectedPart) {
      return;
    }
    setDraft(partToDraft(selectedPart));
    setOfferDraft(partToOfferDraft(selectedPart));
    setManualPrice(String(selectedPart.price ?? ''));
    setManualPriceReason('');
  }, [selectedPart]);

  useEffect(() => {
    if (selectedSource) {
      setSourceDraft(sourceToDraft(selectedSource));
    }
  }, [selectedSource]);

  useEffect(() => {
    if (selectedPost) {
      setPostDraft(postToDraft(selectedPost));
    }
  }, [selectedPost]);

  useEffect(() => {
    if (selectedCandidate) {
      setCandidateDraft(candidateToDraft(selectedCandidate));
    }
  }, [selectedCandidate]);

  useEffect(() => {
    if (selectedAliasReviewItem) {
      setAliasReviewDraft({
        aliasText: selectedAliasReviewItem.aliasText ?? selectedAliasReviewItem.partName ?? '',
        category: selectedAliasReviewItem.category ?? 'GPU',
        targetField: selectedAliasReviewItem.targetField ?? 'rank',
        canonicalValue: selectedAliasReviewItem.canonicalSuggestion ?? selectedAliasReviewItem.rawValue ?? '',
        note: selectedAliasReviewItem.message ?? ''
      });
    }
  }, [selectedAliasReviewItem]);

  const refreshPartQueries = async (partId?: string) => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['admin-parts'] }),
      queryClient.invalidateQueries({ queryKey: ['admin-parts-quality-report'] }),
      queryClient.invalidateQueries({ queryKey: ['parts'] }),
      partId ? queryClient.invalidateQueries({ queryKey: ['admin-part-detail', partId] }) : Promise.resolve()
    ]);
  };
  const refreshIntakeQueries = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['admin-manufacturer-sources'] }),
      queryClient.invalidateQueries({ queryKey: ['admin-manufacturer-posts'] }),
      queryClient.invalidateQueries({ queryKey: ['admin-manufacturer-release-candidates'] }),
      queryClient.invalidateQueries({ queryKey: ['admin-parts-quality-report'] }),
      queryClient.invalidateQueries({ queryKey: ['admin-part-alias-review-items'] }),
      queryClient.invalidateQueries({ queryKey: ['admin-part-alias-review-summary'] }),
      queryClient.invalidateQueries({ queryKey: ['admin-part-alias-rules'] }),
      queryClient.invalidateQueries({ queryKey: ['admin-parts'] })
    ]);
  };
  const openInactivePartDraft = async (partId?: string | null) => {
    if (!partId) {
      await refreshIntakeQueries();
      return;
    }
    setSelectedPartId(partId);
    setPartsListOpen(true);
    setActiveTab('기본 정보');
    window.scrollTo({ top: 0, behavior: 'smooth' });
    await Promise.all([
      refreshIntakeQueries(),
      refreshPartQueries(partId)
    ]);
  };

  const createMutation = useMutation({
    mutationFn: createAdminPart,
    onSuccess: async (part) => {
      setSelectedPartId(part.id);
      await refreshPartQueries(part.id);
    }
  });
  const updateMutation = useMutation({
    mutationFn: ({ partId, payload }: { partId: string; payload: AdminPartPayload }) => updateAdminPart(partId, payload),
    onSuccess: async (part) => {
      setSelectedPartId(part.id);
      await refreshPartQueries(part.id);
    }
  });
  const deleteMutation = useMutation({
    mutationFn: deleteAdminPart,
    onSuccess: async (_, partId) => {
      await refreshPartQueries(partId);
    }
  });
  const restoreMutation = useMutation({
    mutationFn: restoreAdminPart,
    onSuccess: async (part) => {
      setSelectedPartId(part.id);
      await refreshPartQueries(part.id);
    }
  });
  const manualPriceMutation = useMutation({
    mutationFn: ({ partId, price, reason }: { partId: string; price: number; reason?: string }) => updateAdminPartManualPrice(partId, { price, reason }),
    onSuccess: async (part) => {
      setManualPrice(String(part.price ?? ''));
      await refreshPartQueries(part.id);
    }
  });
  const offerMutation = useMutation({
    mutationFn: ({ partId, payload }: { partId: string; payload: AdminExternalOfferPayload }) => updateAdminPartExternalOffer(partId, payload),
    onSuccess: async (part) => {
      await refreshPartQueries(part.id);
    }
  });

  const scanMutation = useMutation({
    mutationFn: scanManufacturerSource,
    onSuccess: refreshIntakeQueries
  });
  const scanAllMutation = useMutation({
    mutationFn: scanAllManufacturerSources,
    onSuccess: refreshIntakeQueries
  });
  const saveSourceMutation = useMutation({
    mutationFn: ({ sourceId, payload }: { sourceId?: string | null; payload: ManufacturerSourcePayload }) => (
      sourceId ? updateManufacturerSource(sourceId, payload) : createManufacturerSource(payload)
    ),
    onSuccess: async (source) => {
      setSelectedSourceId(source.id);
      await refreshIntakeQueries();
    }
  });
  const deleteSourceMutation = useMutation({
    mutationFn: deleteManufacturerSource,
    onSuccess: refreshIntakeQueries
  });
  const restoreSourceMutation = useMutation({
    mutationFn: restoreManufacturerSource,
    onSuccess: async (source) => {
      setSelectedSourceId(source.id);
      await refreshIntakeQueries();
    }
  });
  const savePostMutation = useMutation({
    mutationFn: ({ postId, payload }: { postId?: string | null; payload: ManufacturerPostPayload }) => (
      postId ? updateManufacturerPost(postId, payload) : createManufacturerPost(payload)
    ),
    onSuccess: async (post) => {
      setSelectedPostId(post.id);
      await refreshIntakeQueries();
    }
  });
  const deletePostMutation = useMutation({
    mutationFn: deleteManufacturerPost,
    onSuccess: refreshIntakeQueries
  });
  const restorePostMutation = useMutation({
    mutationFn: restoreManufacturerPost,
    onSuccess: async (post) => {
      setSelectedPostId(post.id);
      await refreshIntakeQueries();
    }
  });
  const createPostCandidateMutation = useMutation({
    mutationFn: createCandidateFromManufacturerPost,
    onSuccess: refreshIntakeQueries
  });
  const createAiAssetDraftMutation = useMutation({
    mutationFn: createAiAssetDraftFromManufacturerPost,
    onSuccess: async (result) => {
      await openInactivePartDraft(result.partId);
    }
  });
  const saveCandidateMutation = useMutation({
    mutationFn: ({ candidateId, payload }: { candidateId: string; payload: CandidatePayload }) => updatePartCatalogCandidate(candidateId, payload),
    onSuccess: async (candidate) => {
      setSelectedCandidateId(candidate.id);
      await refreshIntakeQueries();
    }
  });
  const deleteCandidateMutation = useMutation({
    mutationFn: deletePartCatalogCandidate,
    onSuccess: refreshIntakeQueries
  });
  const restoreCandidateMutation = useMutation({
    mutationFn: restorePartCatalogCandidate,
    onSuccess: async (candidate) => {
      setSelectedCandidateId(candidate.id);
      await refreshIntakeQueries();
    }
  });
  const approveMutation = useMutation({
    mutationFn: approvePartCatalogCandidate,
    onSuccess: async (result) => {
      await openInactivePartDraft(result.publishedPartId);
    }
  });
  const rejectMutation = useMutation({
    mutationFn: rejectPartCatalogCandidate,
    onSuccess: refreshIntakeQueries
  });
  const refreshOffersMutation = useMutation({
    mutationFn: refreshPartCatalogCandidateOffers,
    onSuccess: refreshIntakeQueries
  });
  const resolveAliasReviewMutation = useMutation({
    mutationFn: ({ itemId, payload }: { itemId: string; payload: AliasReviewDraft }) => resolvePartAliasReviewItem(itemId, payload),
    onSuccess: async () => {
      setSelectedAliasReviewId(null);
      setAliasReviewDraft(DEFAULT_ALIAS_REVIEW_DRAFT);
      await refreshIntakeQueries();
    }
  });
  const ignoreAliasReviewMutation = useMutation({
    mutationFn: ({ itemId, note }: { itemId: string; note?: string }) => ignorePartAliasReviewItem(itemId, note),
    onSuccess: async () => {
      setSelectedAliasReviewId(null);
      setAliasReviewDraft(DEFAULT_ALIAS_REVIEW_DRAFT);
      await refreshIntakeQueries();
    }
  });
  const createAliasRuleMutation = useMutation({
    mutationFn: createPartAliasRule,
    onSuccess: refreshIntakeQueries
  });

  const selectedMutationError = createMutation.error
    ?? updateMutation.error
    ?? deleteMutation.error
    ?? restoreMutation.error
    ?? manualPriceMutation.error
    ?? offerMutation.error;
  const intakeMutationError = scanMutation.error
    ?? scanAllMutation.error
    ?? saveSourceMutation.error
    ?? deleteSourceMutation.error
    ?? restoreSourceMutation.error
    ?? savePostMutation.error
    ?? deletePostMutation.error
    ?? restorePostMutation.error
    ?? createPostCandidateMutation.error
    ?? createAiAssetDraftMutation.error
    ?? saveCandidateMutation.error
    ?? deleteCandidateMutation.error
    ?? restoreCandidateMutation.error
    ?? approveMutation.error
    ?? rejectMutation.error
    ?? refreshOffersMutation.error
    ?? resolveAliasReviewMutation.error
    ?? ignoreAliasReviewMutation.error
    ?? createAliasRuleMutation.error;
  const intakePending = scanMutation.isPending
    || scanAllMutation.isPending
    || saveSourceMutation.isPending
    || deleteSourceMutation.isPending
    || restoreSourceMutation.isPending
    || savePostMutation.isPending
    || deletePostMutation.isPending
    || restorePostMutation.isPending
    || createPostCandidateMutation.isPending
    || createAiAssetDraftMutation.isPending
    || saveCandidateMutation.isPending
    || deleteCandidateMutation.isPending
    || restoreCandidateMutation.isPending
    || approveMutation.isPending
    || rejectMutation.isPending
    || refreshOffersMutation.isPending
    || resolveAliasReviewMutation.isPending
    || ignoreAliasReviewMutation.isPending
    || createAliasRuleMutation.isPending;
  const specFields = useMemo(() => [
    ...COMMON_SPEC_FIELDS,
    ...(CATEGORY_SPEC_FIELDS[draft.category] ?? [])
  ], [draft.category]);

  const handleNewPart = () => {
    setSelectedPartId(null);
    setDraft(DEFAULT_DRAFT);
    setOfferDraft(DEFAULT_OFFER);
    setManualPrice('0');
    setManualPriceReason('');
    setActiveTab('기본 정보');
  };
  const saveDraft = () => {
    const payload = draftToPayload(draft);
    if (selectedPartId) {
      updateMutation.mutate({ partId: selectedPartId, payload });
    } else {
      createMutation.mutate(payload);
    }
  };
  const changeStatus = (status: string) => {
    if (!selectedPartId) {
      return;
    }
    const message = status === 'ACTIVE'
      ? '필수 Tool 스펙이 모두 있어야 ACTIVE로 게시됩니다. 계속할까요?'
      : `${status} 상태로 변경할까요?`;
    if (window.confirm(message)) {
      updateMutation.mutate({ partId: selectedPartId, payload: { ...draftToPayload(draft), status } });
    }
  };
  const softDelete = () => {
    if (selectedPartId && window.confirm('이 부품을 삭제 상태로 전환할까요? 사용자 화면과 추천 대상에서 제외됩니다.')) {
      deleteMutation.mutate(selectedPartId);
    }
  };
  const restore = () => {
    if (selectedPartId && window.confirm('이 부품을 INACTIVE 초안으로 복구할까요?')) {
      restoreMutation.mutate(selectedPartId);
    }
  };
  const saveManualPrice = () => {
    if (!selectedPartId) {
      return;
    }
    const price = Number(manualPrice);
    if (!Number.isFinite(price) || price < 0) {
      window.alert('대표 가격은 0 이상의 숫자여야 합니다.');
      return;
    }
    if (window.confirm('대표 가격을 수동 보정하고 ADMIN_MANUAL 가격 이력을 남길까요?')) {
      manualPriceMutation.mutate({ partId: selectedPartId, price, reason: manualPriceReason || undefined });
    }
  };
  const saveOffer = () => {
    if (!selectedPartId) {
      return;
    }
    offerMutation.mutate({
      partId: selectedPartId,
      payload: {
        searchQuery: emptyToNull(offerDraft.searchQuery),
        title: emptyToNull(offerDraft.title),
        imageUrl: emptyToNull(offerDraft.imageUrl),
        supplierName: emptyToNull(offerDraft.supplierName),
        offerUrl: emptyToNull(offerDraft.offerUrl),
        lowPrice: numberOrNull(offerDraft.lowPrice)
      }
    });
  };
  const newSource = () => {
    setSelectedSourceId(null);
    setSourceDraft(DEFAULT_SOURCE_DRAFT);
  };
  const saveSource = () => {
    let parserConfig: Record<string, unknown> = {};
    try {
      parserConfig = sourceDraft.parserConfigText.trim() ? JSON.parse(sourceDraft.parserConfigText) : {};
    } catch {
      window.alert('고급 파서 설정 JSON 형식을 확인하십시오.');
      return;
    }
    const payload: ManufacturerSourcePayload = {
      manufacturer: sourceDraft.manufacturer.trim(),
      categoryScope: sourceDraft.categoryScope,
      sourceType: sourceDraft.sourceType,
      sourceUrl: sourceDraft.sourceUrl.trim(),
      enabled: sourceDraft.enabled,
      pollIntervalMinutes: Number(sourceDraft.pollIntervalMinutes || 1440),
      status: sourceDraft.status,
      parserConfig
    };
    saveSourceMutation.mutate({ sourceId: selectedSourceId, payload });
  };
  const removeSource = () => {
    if (selectedSourceId && window.confirm('이 제조사 source를 삭제 상태로 전환할까요? 전체 scan 대상에서 제외됩니다.')) {
      deleteSourceMutation.mutate(selectedSourceId);
    }
  };
  const restoreSource = () => {
    if (selectedSourceId && window.confirm('이 제조사 source를 PAUSED 상태로 복구할까요?')) {
      restoreSourceMutation.mutate(selectedSourceId);
    }
  };
  const newPost = () => {
    setSelectedPostId(null);
    setPostDraft({
      ...DEFAULT_POST_DRAFT,
      sourceId: selectedSourceId ?? sources[0]?.id ?? ''
    });
  };
  const savePost = () => {
    const payload: ManufacturerPostPayload = {
      sourceId: postDraft.sourceId,
      externalUrl: postDraft.externalUrl.trim(),
      title: postDraft.title.trim(),
      publishedAt: emptyToNull(postDraft.publishedAt),
      excerpt: emptyToNull(postDraft.excerpt),
      classificationStatus: postDraft.classificationStatus,
      detectedCategory: emptyToNull(postDraft.detectedCategory),
      detectedProductName: emptyToNull(postDraft.detectedProductName),
      confidence: numberOrNull(postDraft.confidence)
    };
    savePostMutation.mutate({ postId: selectedPostId, payload });
  };
  const removePost = () => {
    if (selectedPostId && window.confirm('이 감지 게시글을 삭제 상태로 전환할까요? 연결된 후보나 part는 삭제하지 않습니다.')) {
      deletePostMutation.mutate(selectedPostId);
    }
  };
  const restorePost = () => {
    if (selectedPostId && window.confirm('이 감지 게시글을 복구할까요?')) {
      restorePostMutation.mutate(selectedPostId);
    }
  };
  const createCandidateFromPost = () => {
    if (selectedPostId && !selectedPostReadyForCandidate) {
      window.alert('후보 생성은 PRODUCT_CANDIDATE 상태와 감지 카테고리/제품명이 저장된 게시글에서만 가능합니다. PENDING 게시글은 AI 초안화를 먼저 실행하십시오.');
      return;
    }
    if (selectedPostId && window.confirm('이 게시글 기준으로 네이버 검색 후보를 생성할까요? parts는 직접 수정하지 않습니다.')) {
      createPostCandidateMutation.mutate(selectedPostId);
    }
  };
  const createAiAssetDraftFromPost = () => {
    if (selectedPostId && window.confirm('AI가 게시글을 구조화하고 네이버 후보를 생성/갱신합니다. INACTIVE 자산 연결은 검수 후 "후보 승인"에서 별도로 진행합니다. 계속할까요?')) {
      createAiAssetDraftMutation.mutate(selectedPostId);
    }
  };
  const saveCandidate = () => {
    if (!selectedCandidateId) {
      return;
    }
    saveCandidateMutation.mutate({
      candidateId: selectedCandidateId,
      payload: {
        category: candidateDraft.category,
        searchQuery: candidateDraft.searchQuery,
        title: candidateDraft.title,
        manufacturerGuess: emptyToNull(candidateDraft.manufacturerGuess),
        imageUrl: emptyToNull(candidateDraft.imageUrl),
        supplierName: emptyToNull(candidateDraft.supplierName),
        offerUrl: emptyToNull(candidateDraft.offerUrl),
        lowPrice: numberOrNull(candidateDraft.lowPrice)
      }
    });
  };
  const removeCandidate = () => {
    if (selectedCandidateId && window.confirm('이 후보를 삭제 상태로 전환할까요? 사용자 화면과 추천 대상에는 노출되지 않습니다.')) {
      deleteCandidateMutation.mutate(selectedCandidateId);
    }
  };
  const restoreCandidate = () => {
    if (selectedCandidateId && window.confirm('이 후보를 복구할까요?')) {
      restoreCandidateMutation.mutate(selectedCandidateId);
    }
  };
  const resolveAliasReview = () => {
    if (!selectedAliasReviewId) {
      window.alert('해결할 검수 항목을 먼저 선택하십시오.');
      return;
    }
    if (!aliasReviewDraft.aliasText.trim() || !aliasReviewDraft.canonicalValue.trim()) {
      window.alert('alias와 canonical 값을 입력해야 합니다.');
      return;
    }
    resolveAliasReviewMutation.mutate({ itemId: selectedAliasReviewId, payload: aliasReviewDraft });
  };
  const ignoreAliasReview = () => {
    if (selectedAliasReviewId && window.confirm('이 검수 항목을 무시 처리할까요?')) {
      ignoreAliasReviewMutation.mutate({ itemId: selectedAliasReviewId, note: aliasReviewDraft.note });
    }
  };
  const createAliasRule = () => {
    if (!aliasReviewDraft.aliasText.trim() || !aliasReviewDraft.canonicalValue.trim()) {
      window.alert('alias와 canonical 값을 입력해야 합니다.');
      return;
    }
    createAliasRuleMutation.mutate(aliasReviewDraft);
  };

  return (
    <AdminShell title="부품 / 가격 관리자">
      {partsListOpen ? (
        <div className="grid grid-cols-[minmax(0,1fr)_440px] gap-5">
          <Panel
            title="부품 DB 관리"
            subtitle="내부 쇼핑몰 자산의 생성, 수정, 게시, 삭제, 대표 가격을 운영합니다."
            action={<SectionToggleButton expanded={partsListOpen} label="부품 DB 관리" onClick={() => setPartsListOpen((open) => !open)} />}
          >
            <>
              <div className="mb-4 grid grid-cols-6 gap-2">
                <select value={filters.category} onChange={(event) => setFilters({ ...filters, category: event.target.value })} className="rounded border border-slate-300 px-3 py-2 text-xs">
                  <option value="">전체 카테고리</option>
                  {CATEGORIES.map((category) => <option key={category} value={category}>{category}</option>)}
                </select>
                <select value={filters.status} onChange={(event) => setFilters({ ...filters, status: event.target.value })} className="rounded border border-slate-300 px-3 py-2 text-xs">
                  <option value="">전체 상태</option>
                  {STATUSES.map((status) => <option key={status} value={status}>{status}</option>)}
                </select>
                <input value={filters.q} onChange={(event) => setFilters({ ...filters, q: event.target.value })} placeholder="제품명/스펙 검색" className="rounded border border-slate-300 px-3 py-2 text-xs" />
                <input value={filters.manufacturer} onChange={(event) => setFilters({ ...filters, manufacturer: event.target.value })} placeholder="제조사" className="rounded border border-slate-300 px-3 py-2 text-xs" />
                <select value={filters.sort} onChange={(event) => setFilters({ ...filters, sort: event.target.value })} className="rounded border border-slate-300 px-3 py-2 text-xs">
                  <option value="category">카테고리순</option>
                  <option value="updated_desc">최근 수정순</option>
                  <option value="price_desc">가격 높은순</option>
                  <option value="price_asc">가격 낮은순</option>
                  <option value="name">이름순</option>
                </select>
                <button type="button" onClick={handleNewPart} className="rounded bg-brand-blue px-3 py-2 text-xs font-black text-white">신규 부품</button>
                <input value={filters.minPrice} onChange={(event) => setFilters({ ...filters, minPrice: event.target.value })} placeholder="최소가" className="rounded border border-slate-300 px-3 py-2 text-xs" />
                <input value={filters.maxPrice} onChange={(event) => setFilters({ ...filters, maxPrice: event.target.value })} placeholder="최대가" className="rounded border border-slate-300 px-3 py-2 text-xs" />
                <label className="col-span-2 flex items-center gap-2 rounded border border-slate-200 px-3 py-2 text-xs font-bold text-slate-600">
                  <input type="checkbox" checked={filters.includeDeleted} onChange={(event) => setFilters({ ...filters, includeDeleted: event.target.checked })} />
                  삭제 항목 포함
                </label>
              </div>
              {adminPartsQuery.isLoading ? <StateMessage type="info" title="부품 DB 로딩 중" body="관리자 parts 목록을 불러오고 있습니다." /> : null}
              {adminPartsQuery.isError ? <StateMessage type="warn" title="부품 DB 조회 실패" body="GET /api/admin/parts 응답을 확인해야 합니다." /> : null}
              {!adminPartsQuery.isLoading && !adminPartsQuery.isError ? (
                <DataTable columns={['category', 'name', 'manufacturer', 'price', 'status', 'tool', 'updated']} rows={adminPartRows(parts, setSelectedPartId)} />
              ) : null}
            </>
          </Panel>
          <Panel title="부품 상세 패널" subtitle={selectedPartId ? 'row 선택 후 수정합니다.' : '신규 부품 초안을 만들 수 있습니다.'}>
          <div className="mb-4 flex gap-2">
            {TABS.map((tab) => (
              <button key={tab} type="button" onClick={() => setActiveTab(tab)} className={`rounded px-3 py-2 text-xs font-black ${activeTab === tab ? 'bg-commerce-ink text-white' : 'border border-slate-300 text-slate-600'}`}>
                {tab}
              </button>
            ))}
          </div>
          {selectedPartQuery.isFetching ? <StateMessage type="info" title="상세 로딩 중" body="선택한 부품 상세를 불러오고 있습니다." /> : null}
          {selectedPartQuery.isError ? <StateMessage type="warn" title="상세 조회 실패" body="GET /api/admin/parts/{id} 응답을 확인해야 합니다." /> : null}
          {selectedMutationError ? <div className="mb-3"><StateMessage type="warn" title="저장 실패" body={selectedMutationError instanceof Error ? selectedMutationError.message : '관리자 부품 API 응답을 확인해야 합니다.'} /></div> : null}
          {activeTab === '기본 정보' ? (
            <div className="space-y-3">
              <FormSelect label="카테고리" value={draft.category} options={CATEGORIES} onChange={(value) => setDraft({ ...draft, category: value, attributes: { ...draft.attributes, categoryForm: value } })} />
              <FormInput label="제품명" value={draft.name} onChange={(value) => setDraft({ ...draft, name: value })} />
              <FormInput label="제조사" value={draft.manufacturer} onChange={(value) => setDraft({ ...draft, manufacturer: value })} />
              <FormInput label="대표 가격" value={draft.price} onChange={(value) => setDraft({ ...draft, price: value })} />
              <FormSelect label="상태" value={draft.status} options={['INACTIVE', 'ACTIVE', 'DISCONTINUED']} onChange={(value) => setDraft({ ...draft, status: value })} />
              {selectedPart?.missingRequiredFields?.length ? (
                <StateMessage type="warn" title="ACTIVE 게시 차단 스펙" body={selectedPart.missingRequiredFields.join(', ')} />
              ) : selectedPart?.toolReady ? (
                <StateMessage type="success" title="Tool 필수 스펙 충족" body="서버 validator 기준으로 ACTIVE 게시 가능한 상태입니다." />
              ) : null}
            </div>
          ) : null}
          {activeTab === '스펙' ? (
            <div className="grid grid-cols-2 gap-3">
              {specFields.map((field) => (
                <SpecFieldControl
                  key={field.key}
                  field={field}
                  value={draft.attributes[field.key]}
                  onChange={(value) => setDraft({ ...draft, attributes: { ...draft.attributes, [field.key]: value } })}
                />
              ))}
            </div>
          ) : null}
          {activeTab === '가격/Offer' ? (
            <div className="space-y-4">
              <div className="rounded border border-slate-200 p-3">
                <div className="text-sm font-black text-commerce-ink">대표가 수동 보정</div>
                <div className="mt-3 grid grid-cols-[1fr_1fr] gap-2">
                  <input value={manualPrice} onChange={(event) => setManualPrice(event.target.value)} placeholder="대표 가격" className="rounded border border-slate-300 px-3 py-2 text-xs" />
                  <input value={manualPriceReason} onChange={(event) => setManualPriceReason(event.target.value)} placeholder="보정 사유" className="rounded border border-slate-300 px-3 py-2 text-xs" />
                </div>
                <button type="button" disabled={!selectedPartId || manualPriceMutation.isPending} onClick={saveManualPrice} className="mt-3 rounded bg-brand-blue px-3 py-2 text-xs font-black text-white disabled:opacity-50">대표가 수동 보정</button>
              </div>
              <div className="rounded border border-slate-200 p-3">
                <div className="text-sm font-black text-commerce-ink">대표 Offer 수동 보정</div>
                <div className="mt-3 space-y-2">
                  <FormInput label="검색어" value={offerDraft.searchQuery} onChange={(value) => setOfferDraft({ ...offerDraft, searchQuery: value })} />
                  <FormInput label="Offer 제목" value={offerDraft.title} onChange={(value) => setOfferDraft({ ...offerDraft, title: value })} />
                  <FormInput label="이미지 URL" value={offerDraft.imageUrl} onChange={(value) => setOfferDraft({ ...offerDraft, imageUrl: value })} />
                  <FormInput label="공급처" value={offerDraft.supplierName} onChange={(value) => setOfferDraft({ ...offerDraft, supplierName: value })} />
                  <FormInput label="구매 URL" value={offerDraft.offerUrl} onChange={(value) => setOfferDraft({ ...offerDraft, offerUrl: value })} />
                  <FormInput label="Offer 가격" value={offerDraft.lowPrice} onChange={(value) => setOfferDraft({ ...offerDraft, lowPrice: value })} />
                </div>
                <button type="button" disabled={!selectedPartId || offerMutation.isPending} onClick={saveOffer} className="mt-3 rounded bg-commerce-ink px-3 py-2 text-xs font-black text-white disabled:opacity-50">Offer 저장</button>
              </div>
            </div>
          ) : null}
          {activeTab === '이력' ? (
            <div className="space-y-3 text-xs text-slate-600">
              <StateMessage type="info" title="Audit 기준" body="생성/수정/상태변경/가격보정/Offer보정/삭제/복구는 admin_audit_logs에 남습니다." />
              <div>생성: {selectedPart?.createdAt ? new Date(selectedPart.createdAt).toLocaleString('ko-KR') : '-'}</div>
              <div>수정: {selectedPart?.updatedAt ? new Date(selectedPart.updatedAt).toLocaleString('ko-KR') : '-'}</div>
              <div>삭제: {selectedPart?.deletedAt ? new Date(selectedPart.deletedAt).toLocaleString('ko-KR') : '-'}</div>
              <div>대표 offer source: {selectedPart?.externalOffer?.source ?? '-'}</div>
            </div>
          ) : null}
          <div className="mt-5 flex flex-wrap gap-2 border-t border-slate-200 pt-4">
            <button type="button" disabled={createMutation.isPending || updateMutation.isPending} onClick={saveDraft} className="rounded bg-brand-blue px-3 py-2 text-xs font-black text-white disabled:opacity-50">저장</button>
            <button type="button" disabled={!selectedPartId || updateMutation.isPending} onClick={() => changeStatus('ACTIVE')} className="rounded bg-emerald-600 px-3 py-2 text-xs font-black text-white disabled:opacity-50">ACTIVE 게시</button>
            <button type="button" disabled={!selectedPartId || updateMutation.isPending} onClick={() => changeStatus('INACTIVE')} className="rounded border border-slate-300 px-3 py-2 text-xs font-black text-slate-700 disabled:opacity-50">INACTIVE 전환</button>
            <button type="button" disabled={!selectedPartId || updateMutation.isPending} onClick={() => changeStatus('DISCONTINUED')} className="rounded border border-amber-300 px-3 py-2 text-xs font-black text-amber-700 disabled:opacity-50">DISCONTINUED 전환</button>
            <button type="button" disabled={!selectedPartId || deleteMutation.isPending} onClick={softDelete} className="rounded border border-rose-300 px-3 py-2 text-xs font-black text-rose-700 disabled:opacity-50">삭제</button>
            <button type="button" disabled={!selectedPartId || restoreMutation.isPending} onClick={restore} className="rounded border border-slate-300 px-3 py-2 text-xs font-black text-slate-700 disabled:opacity-50">복구</button>
          </div>
          </Panel>
        </div>
      ) : (
        <Panel
          title="부품 DB 관리"
          subtitle="내부 쇼핑몰 자산 운영 영역을 접었습니다."
          action={<SectionToggleButton expanded={partsListOpen} label="부품 DB 관리" onClick={() => setPartsListOpen((open) => !open)} />}
        >
          <StateMessage type="info" title="부품 DB 운영 접힘" body="펼치기를 누르면 자산 목록과 편집 폼을 함께 다시 표시합니다." />
        </Panel>
      )}

      <div className="mt-5">
        <Panel
          title="제조사 신제품 감지 운영"
          subtitle="공식 제조사 source, 감지 게시글, 신제품 후보를 관리합니다. 후보 승인 전에는 사용자 화면과 추천 대상에 노출되지 않습니다."
          action={<SectionToggleButton expanded={manufacturerIntakeOpen} label="제조사 신제품 감지 운영" onClick={() => setManufacturerIntakeOpen((open) => !open)} />}
        >
          {manufacturerIntakeOpen ? (
            <>
              <div className="mb-4 flex flex-wrap gap-2 border-b border-slate-200 pb-3">
                {INTAKE_TABS.map((tab) => (
                  <button
                    key={tab}
                    type="button"
                    onClick={() => setIntakeTab(tab)}
                    className={`rounded px-4 py-2 text-xs font-black ${intakeTab === tab ? 'bg-commerce-ink text-white' : 'border border-slate-300 text-slate-700'}`}
                  >
                    {tab}
                  </button>
                ))}
              </div>
              {intakeMutationError ? (
                <div className="mb-4">
                  <StateMessage type="warn" title="신제품 감지 처리 실패" body={intakeMutationError instanceof Error ? intakeMutationError.message : '신제품 감지 API 응답을 확인해야 합니다.'} />
                </div>
              ) : null}
              {scanMutation.data ? (
                <div className="mb-4">
                  <StateMessage
                    type={scanMutation.data.failed ? 'warn' : 'success'}
                    title={scanMutation.data.failed ? 'scan 실패 source 기록' : 'scan 완료'}
                    body={scanMutation.data.failed
                      ? `${scanMutation.data.manufacturer ?? '제조사 source'} 응답 실패: ${scanMutation.data.errorSummary ?? '외부 source 응답을 확인해야 합니다.'}`
                      : `게시글 ${scanMutation.data.parsedPosts ?? 0}개 확인, 신규 ${scanMutation.data.newPosts ?? 0}개, 제품 후보 ${scanMutation.data.productPosts ?? 0}개, 후보 생성 ${scanMutation.data.createdCandidates ?? 0}개`}
                  />
                </div>
              ) : null}
              {scanAllMutation.data ? (
                <div className="mb-4">
                  <StateMessage
                    type={scanAllMutation.data.failedSources ? 'warn' : 'success'}
                    title={scanAllMutation.data.failedSources ? '전체 scan 일부 실패' : '전체 scan 완료'}
                    body={`source ${scanAllMutation.data.scannedSources}개 확인, 신규 게시글 ${scanAllMutation.data.newPosts}개, 후보 생성 ${scanAllMutation.data.createdCandidates}개, 실패 source ${scanAllMutation.data.failedSources ?? 0}개`}
                  />
                </div>
              ) : null}
              {approveMutation.data ? <div className="mb-4"><StateMessage type="success" title="INACTIVE 초안 생성" body={`후보가 parts 초안으로 연결되었습니다. 상태: ${approveMutation.data.partStatus ?? approveMutation.data.status}`} /></div> : null}
              {createAiAssetDraftMutation.data ? (
                <div className="mb-4">
                  <StateMessage
                    type={createAiAssetDraftMutation.data.candidateId ? 'success' : 'info'}
                    title={createAiAssetDraftMutation.data.candidateId ? 'AI 초안 · 후보 검수 대기' : 'AI 후보 정리 완료'}
                    body={(createAiAssetDraftMutation.data.messages ?? []).join(' / ') || 'AI 자산화 결과를 반영했습니다.'}
                  />
                </div>
              ) : null}
              {rejectMutation.data ? <div className="mb-4"><StateMessage type="success" title="후보 거절 완료" body="후보가 REJECTED 상태로 변경되었습니다." /></div> : null}
              {refreshOffersMutation.data ? <div className="mb-4"><StateMessage type="success" title="offer 재검색 완료" body={`후보 검색 결과를 갱신했습니다. 현재가: ${refreshOffersMutation.data.lowPrice ? `${refreshOffersMutation.data.lowPrice.toLocaleString()}원` : '없음'}`} /></div> : null}

          {intakeTab === '추적 Source' ? (
            <div className="grid grid-cols-[minmax(0,1fr)_420px] gap-5">
              <div>
                <div className="mb-3 flex items-center justify-between rounded-md border border-slate-200 bg-slate-50 px-4 py-3">
                  <div>
                    <div className="text-sm font-black text-commerce-ink">활성 source 전체 scan</div>
                    <div className="mt-1 text-xs text-slate-500">enabled=true, PAUSED 아님, 삭제 아님 source만 확인합니다.</div>
                  </div>
                  <button type="button" disabled={intakePending} onClick={() => scanAllMutation.mutate()} className="rounded bg-brand-blue px-4 py-2 text-xs font-black text-white disabled:opacity-50">
                    {scanAllMutation.isPending ? '전체 scan 중' : '전체 scan'}
                  </button>
                </div>
                {sourcesQuery.isLoading ? <StateMessage type="info" title="제조사 source 로딩 중" body="감시할 공식 제조사 게시판 목록을 불러오고 있습니다." /> : null}
                {sourcesQuery.isError ? <StateMessage type="warn" title="제조사 source 조회 실패" body="GET /api/admin/manufacturer-sources 응답을 확인해야 합니다." /> : null}
                {!sourcesQuery.isLoading && !sourcesQuery.isError ? (
                  sources.length > 0
                    ? <DataTable columns={['manufacturer', 'scope', 'type', 'enabled', 'status', 'source', 'lastScan', 'action']} rows={sourceRows(sources, setSelectedSourceId, scanMutation.mutate, intakePending)} />
                    : <StateMessage type="warn" title="등록된 제조사 source 없음" body="오른쪽 폼에서 공식 제조사 source를 등록하십시오." />
                ) : null}
              </div>
              <div className="rounded-md border border-slate-200 p-4">
                <div className="mb-3 flex items-center justify-between">
                  <div className="text-sm font-black text-commerce-ink">{selectedSourceId ? 'Source 수정' : '새 Source'}</div>
                  <button type="button" onClick={newSource} className="rounded border border-slate-300 px-3 py-2 text-xs font-black text-slate-700">새 source</button>
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <FormInput label="제조사" value={sourceDraft.manufacturer} onChange={(value) => setSourceDraft({ ...sourceDraft, manufacturer: value })} />
                  <FormSelect label="카테고리 범위" value={sourceDraft.categoryScope} options={['ALL', ...CATEGORIES]} onChange={(value) => setSourceDraft({ ...sourceDraft, categoryScope: value })} />
                  <FormSelect label="Source Type" value={sourceDraft.sourceType} options={SOURCE_TYPES} onChange={(value) => setSourceDraft({ ...sourceDraft, sourceType: value })} />
                  <FormSelect label="상태" value={sourceDraft.status} options={SOURCE_STATUSES} onChange={(value) => setSourceDraft({ ...sourceDraft, status: value })} />
                  <FormInput label="폴링 주기(분)" value={sourceDraft.pollIntervalMinutes} onChange={(value) => setSourceDraft({ ...sourceDraft, pollIntervalMinutes: value })} />
                  <label className="mt-5 flex items-center gap-2 text-xs font-bold text-slate-600">
                    <input type="checkbox" checked={sourceDraft.enabled} onChange={(event) => setSourceDraft({ ...sourceDraft, enabled: event.target.checked })} />
                    전체 scan 포함
                  </label>
                </div>
                <div className="mt-3">
                  <FormInput label="공식 제조사 URL" value={sourceDraft.sourceUrl} onChange={(value) => setSourceDraft({ ...sourceDraft, sourceUrl: value })} />
                </div>
                <details className="mt-3 rounded border border-slate-200 p-3">
                  <summary className="cursor-pointer text-xs font-black text-slate-700">고급 파서 설정 JSON</summary>
                  <textarea value={sourceDraft.parserConfigText} onChange={(event) => setSourceDraft({ ...sourceDraft, parserConfigText: event.target.value })} className="mt-3 h-32 w-full rounded border border-slate-300 px-3 py-2 font-mono text-xs" />
                </details>
                <div className="mt-4 flex flex-wrap gap-2">
                  <button type="button" disabled={intakePending} onClick={saveSource} className="rounded bg-brand-blue px-3 py-2 text-xs font-black text-white disabled:opacity-50">저장</button>
                  <button type="button" disabled={!selectedSourceId || intakePending} onClick={removeSource} className="rounded border border-rose-300 px-3 py-2 text-xs font-black text-rose-700 disabled:opacity-50">삭제</button>
                  <button type="button" disabled={!selectedSourceId || intakePending} onClick={restoreSource} className="rounded border border-slate-300 px-3 py-2 text-xs font-black text-slate-700 disabled:opacity-50">복구</button>
                </div>
              </div>
            </div>
          ) : null}

          {intakeTab === '감지 게시글' ? (
            <div className="grid grid-cols-[minmax(0,1fr)_420px] gap-5">
              <div>
                {postsQuery.isLoading ? <StateMessage type="info" title="게시글 로딩 중" body="manufacturer_posts에서 최근 감지 결과를 불러오고 있습니다." /> : null}
                {postsQuery.isError ? <StateMessage type="warn" title="게시글 조회 실패" body="GET /api/admin/manufacturer-posts 응답을 확인해야 합니다." /> : null}
                {!postsQuery.isLoading && !postsQuery.isError ? (
                  posts.length > 0
                    ? <DataTable columns={['title', 'status', 'category', 'candidate', 'detectedAt', 'action']} rows={postRows(posts, setSelectedPostId, createPostCandidateMutation.mutate, createAiAssetDraftMutation.mutate, intakePending)} />
                    : <StateMessage type="info" title="감지 게시글 없음" body="source scan 또는 오른쪽 수동 등록을 실행하면 이 영역에 기록됩니다." />
                ) : null}
              </div>
              <div className="rounded-md border border-slate-200 p-4">
                <div className="mb-3 flex items-center justify-between">
                  <div className="text-sm font-black text-commerce-ink">{selectedPostId ? '게시글 수정' : '게시글 수동 등록'}</div>
                  <button type="button" onClick={newPost} className="rounded border border-slate-300 px-3 py-2 text-xs font-black text-slate-700">새 게시글</button>
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <FormSelect label="Source" value={postDraft.sourceId} options={sources.map((source) => source.id)} onChange={(value) => setPostDraft({ ...postDraft, sourceId: value })} />
                  <FormSelect label="분류 상태" value={postDraft.classificationStatus} options={POST_STATUSES} onChange={(value) => setPostDraft({ ...postDraft, classificationStatus: value })} />
                  <FormSelect label="감지 카테고리" value={postDraft.detectedCategory} options={['', ...CATEGORIES]} onChange={(value) => setPostDraft({ ...postDraft, detectedCategory: value })} />
                  <FormInput label="확신도(0~1)" value={postDraft.confidence} onChange={(value) => setPostDraft({ ...postDraft, confidence: value })} />
                </div>
                <div className="mt-3 space-y-3">
                  <FormInput label="공식 게시글 URL" value={postDraft.externalUrl} onChange={(value) => setPostDraft({ ...postDraft, externalUrl: value })} />
                  <FormInput label="제목" value={postDraft.title} onChange={(value) => setPostDraft({ ...postDraft, title: value })} />
                  <FormInput label="감지 제품명" value={postDraft.detectedProductName} onChange={(value) => setPostDraft({ ...postDraft, detectedProductName: value })} />
                  <FormInput label="게시일(ISO 선택)" value={postDraft.publishedAt} onChange={(value) => setPostDraft({ ...postDraft, publishedAt: value })} />
                  <textarea value={postDraft.excerpt} onChange={(event) => setPostDraft({ ...postDraft, excerpt: event.target.value })} placeholder="게시글 요약" className="h-24 w-full rounded border border-slate-300 px-3 py-2 text-xs" />
                </div>
                <div className="mt-4 flex flex-wrap gap-2">
                  <button type="button" disabled={intakePending} onClick={savePost} className="rounded bg-brand-blue px-3 py-2 text-xs font-black text-white disabled:opacity-50">저장</button>
                  <button type="button" disabled={!selectedPostId || !selectedPostReadyForCandidate || intakePending} onClick={createCandidateFromPost} className="rounded border border-brand-blue px-3 py-2 text-xs font-black text-brand-blue disabled:opacity-50">후보 생성</button>
                  <button type="button" disabled={!selectedPostId || intakePending} onClick={createAiAssetDraftFromPost} className="rounded bg-commerce-ink px-3 py-2 text-xs font-black text-white disabled:opacity-50">AI 후보 생성 + INACTIVE 초안화</button>
                  <button type="button" disabled={!selectedPostId || intakePending} onClick={removePost} className="rounded border border-rose-300 px-3 py-2 text-xs font-black text-rose-700 disabled:opacity-50">삭제</button>
                  <button type="button" disabled={!selectedPostId || intakePending} onClick={restorePost} className="rounded border border-slate-300 px-3 py-2 text-xs font-black text-slate-700 disabled:opacity-50">복구</button>
                </div>
              </div>
            </div>
          ) : null}

          {intakeTab === '신제품 후보함' ? (
            <div className="grid grid-cols-[minmax(0,1fr)_420px] gap-5">
              <div>
                {candidatesQuery.isLoading ? <StateMessage type="info" title="후보 로딩 중" body="part_catalog_candidates에서 신제품 후보를 불러오고 있습니다." /> : null}
                {candidatesQuery.isError ? <StateMessage type="warn" title="후보 조회 실패" body="GET /api/admin/part-catalog-candidates 응답을 확인해야 합니다." /> : null}
                {!candidatesQuery.isLoading && !candidatesQuery.isError ? (
                  candidates.length > 0
                    ? <DataTable columns={['category', 'title', 'price', 'supplier', 'status', 'part', 'action']} rows={candidateRows(candidates, setSelectedCandidateId, approveMutation.mutate, rejectMutation.mutate, refreshOffersMutation.mutate, openInactivePartDraft, intakePending)} />
                    : <StateMessage type="info" title="검색 후보 없음" body="scan은 성공했지만 네이버 API 설정이 없거나 적절한 상품 검색 결과가 없으면 후보는 생성되지 않습니다." />
                ) : null}
              </div>
              <div className="rounded-md border border-slate-200 p-4">
                <div className="mb-3 text-sm font-black text-commerce-ink">후보 상세 보정</div>
                <div className="grid grid-cols-2 gap-3">
                  <FormSelect label="카테고리" value={candidateDraft.category} options={CATEGORIES} onChange={(value) => setCandidateDraft({ ...candidateDraft, category: value })} />
                  <FormInput label="가격" value={candidateDraft.lowPrice} onChange={(value) => setCandidateDraft({ ...candidateDraft, lowPrice: value })} />
                  <FormInput label="제조사 추정" value={candidateDraft.manufacturerGuess} onChange={(value) => setCandidateDraft({ ...candidateDraft, manufacturerGuess: value })} />
                  <FormInput label="공급처" value={candidateDraft.supplierName} onChange={(value) => setCandidateDraft({ ...candidateDraft, supplierName: value })} />
                </div>
                <div className="mt-3 space-y-3">
                  <FormInput label="상품 후보 제목" value={candidateDraft.title} onChange={(value) => setCandidateDraft({ ...candidateDraft, title: value })} />
                  <FormInput label="검색어" value={candidateDraft.searchQuery} onChange={(value) => setCandidateDraft({ ...candidateDraft, searchQuery: value })} />
                  <FormInput label="이미지 URL" value={candidateDraft.imageUrl} onChange={(value) => setCandidateDraft({ ...candidateDraft, imageUrl: value })} />
                  <FormInput label="구매 URL" value={candidateDraft.offerUrl} onChange={(value) => setCandidateDraft({ ...candidateDraft, offerUrl: value })} />
                </div>
                <div className="mt-4 flex flex-wrap gap-2">
                  <button type="button" disabled={!selectedCandidateId || intakePending} onClick={saveCandidate} className="rounded bg-brand-blue px-3 py-2 text-xs font-black text-white disabled:opacity-50">저장</button>
                  <button type="button" disabled={!selectedCandidateId || intakePending} onClick={() => selectedCandidateId && refreshOffersMutation.mutate(selectedCandidateId)} className="rounded border border-brand-blue px-3 py-2 text-xs font-black text-brand-blue disabled:opacity-50">offer 재검색</button>
                  {selectedCandidate?.publishedPartId ? (
                    <button type="button" disabled={intakePending} onClick={() => openInactivePartDraft(selectedCandidate.publishedPartId)} className="rounded bg-emerald-600 px-3 py-2 text-xs font-black text-white disabled:opacity-50">초안 열기</button>
                  ) : (
                    <button type="button" disabled={!selectedCandidateId || !selectedCandidateCanApprove || intakePending} onClick={() => selectedCandidateId && approveMutation.mutate(selectedCandidateId)} className="rounded bg-emerald-600 px-3 py-2 text-xs font-black text-white disabled:opacity-50">INACTIVE 승인</button>
                  )}
                  <button type="button" disabled={!selectedCandidateId || Boolean(selectedCandidate?.publishedPartId) || intakePending} title={selectedCandidate?.publishedPartId ? '이미 INACTIVE 초안으로 승인된 후보는 연결된 부품 초안을 삭제/중단한 뒤 처리하십시오.' : undefined} onClick={() => selectedCandidateId && rejectMutation.mutate(selectedCandidateId)} className="rounded border border-slate-300 px-3 py-2 text-xs font-black text-slate-700 disabled:opacity-50">거절</button>
                  <button type="button" disabled={!selectedCandidateId || intakePending} onClick={removeCandidate} className="rounded border border-rose-300 px-3 py-2 text-xs font-black text-rose-700 disabled:opacity-50">삭제</button>
                  <button type="button" disabled={!selectedCandidateId || intakePending} onClick={restoreCandidate} className="rounded border border-slate-300 px-3 py-2 text-xs font-black text-slate-700 disabled:opacity-50">복구</button>
                </div>
              </div>
            </div>
          ) : null}

          {intakeTab === '자산 품질 점검' ? (
            <div className="space-y-5">
              {qualityReportQuery.isLoading ? <StateMessage type="info" title="품질 리포트 로딩 중" body="ACTIVE 내부 자산 기준으로 Tool-ready, 벤치마크, FPS gap, alias 검수 큐를 집계하고 있습니다." /> : null}
              {qualityReportQuery.isError ? <StateMessage type="warn" title="품질 리포트 조회 실패" body="GET /api/admin/parts/quality-report 응답을 확인해야 합니다." /> : null}
              {!qualityReportQuery.isLoading && !qualityReportQuery.isError && qualityReport ? (
                <>
                  <div className="grid grid-cols-6 gap-3">
                    <MetricCard label="ACTIVE 자산" value={qualityReport.summary.activeParts} />
                    <MetricCard label="Tool-ready 누락" value={qualityReport.summary.toolReadyMissing} tone={qualityReport.summary.toolReadyMissing ? 'warn' : 'ok'} />
                    <MetricCard label="필수 스펙 누락" value={qualityReport.summary.requiredSpecMissing} tone={qualityReport.summary.requiredSpecMissing ? 'warn' : 'ok'} />
                    <MetricCard label="벤치마크 누락" value={qualityReport.summary.benchmarkMissing} tone={qualityReport.summary.benchmarkMissing ? 'warn' : 'ok'} />
                    <MetricCard label="FPS gap" value={qualityReport.summary.fpsCoverageGap} tone={qualityReport.summary.fpsCoverageGap ? 'warn' : 'ok'} />
                    <MetricCard label="열린 검수 큐" value={qualityReport.summary.aliasReviewOpen} tone={qualityReport.summary.aliasReviewOpen ? 'warn' : 'ok'} />
                  </div>
                  <div>
                    <div className="mb-2 flex items-center justify-between">
                      <div className="text-sm font-black text-commerce-ink">카테고리별 품질 현황</div>
                      <div className="text-xs font-bold text-slate-500">생성 시각: {qualityReport.generatedAt ? new Date(qualityReport.generatedAt).toLocaleString('ko-KR') : '-'}</div>
                    </div>
                    <DataTable
                      columns={['category', 'active', 'tool', 'spec', 'benchmark', 'fps', 'alias']}
                      rows={qualityReportCategoryRows(qualityReport.categories)}
                    />
                  </div>
                  <div>
                    <div className="mb-2 text-sm font-black text-commerce-ink">조치 필요 항목</div>
                    {qualityReport.actionItems.length > 0
                      ? <DataTable columns={['type', 'category', 'label', 'message', 'field/source']} rows={qualityReportActionRows(qualityReport.actionItems)} />
                      : <StateMessage type="success" title="우선 조치 항목 없음" body="현재 품질 리포트 기준으로 즉시 조치할 누락 항목이 없습니다." />}
                  </div>
                </>
              ) : null}
            </div>
          ) : null}

          {intakeTab === 'Alias/스펙 검수 큐' ? (
            <div className="grid grid-cols-[minmax(0,1fr)_420px] gap-5">
              <div className="space-y-5">
                <div>
                  <div className="mb-3 rounded-md border border-slate-200 bg-slate-50 p-3">
                    <div className="mb-2 flex items-center justify-between">
                      <div className="text-sm font-black text-commerce-ink">검수 큐 필터</div>
                      <div className="text-xs font-bold text-slate-500">열린 항목 {aliasReviewQuery.data?.total ?? 0}개</div>
                    </div>
                    <div className="grid grid-cols-4 gap-2">
                      <FormSelect label="상태" value={aliasReviewFilters.status} options={ALIAS_REVIEW_STATUSES} onChange={(value) => setAliasReviewFilters({ ...aliasReviewFilters, status: value })} />
                      <FormSelect label="카테고리" value={aliasReviewFilters.category} options={['', ...CATEGORIES]} onChange={(value) => setAliasReviewFilters({ ...aliasReviewFilters, category: value })} />
                      <FormInput label="대상 필드" value={aliasReviewFilters.targetField} onChange={(value) => setAliasReviewFilters({ ...aliasReviewFilters, targetField: value })} />
                      <FormInput label="Source Type" value={aliasReviewFilters.sourceType} onChange={(value) => setAliasReviewFilters({ ...aliasReviewFilters, sourceType: value })} />
                    </div>
                    {aliasReviewSummary.length > 0 ? (
                      <div className="mt-3 flex flex-wrap gap-2">
                        {aliasReviewSummary.slice(0, 8).map((item, index) => (
                          <span key={`${item.category}-${item.targetField}-${item.sourceType}-${index}`} className="rounded-full bg-white px-3 py-1 text-[11px] font-black text-slate-600 ring-1 ring-slate-200">
                            {item.category ?? '전체'} / {item.targetField ?? '-'} / {item.sourceType ?? '-'}: {item.count}
                          </span>
                        ))}
                      </div>
                    ) : null}
                  </div>
                  {aliasReviewQuery.isLoading ? <StateMessage type="info" title="검수 큐 로딩 중" body="AI 추천 중 rank/alias 판단이 어려웠던 항목을 불러오고 있습니다." /> : null}
                  {aliasReviewQuery.isError ? <StateMessage type="warn" title="검수 큐 조회 실패" body="GET /api/admin/part-alias-review-items 응답을 확인해야 합니다." /> : null}
                  {!aliasReviewQuery.isLoading && !aliasReviewQuery.isError ? (
                    aliasReviewItems.length > 0
                      ? <DataTable columns={['category', 'field', 'alias', 'raw', 'source', 'status', 'created']} rows={aliasReviewRows(aliasReviewItems, setSelectedAliasReviewId)} />
                      : <StateMessage type="info" title="열린 검수 항목 없음" body="AI가 rank/alias를 확신하지 못한 경우 이 큐에 항목이 쌓입니다." />
                  ) : null}
                </div>
                <div>
                  <div className="mb-2 text-sm font-black text-commerce-ink">등록된 alias rule</div>
                  {aliasRules.length > 0
                    ? <DataTable columns={['category', 'field', 'alias', 'canonical', 'source']} rows={aliasRuleRows(aliasRules)} />
                    : <StateMessage type="info" title="등록된 alias rule 없음" body="검수 항목을 해결하면 alias rule이 생성됩니다." />}
                </div>
              </div>
              <div className="rounded-md border border-slate-200 p-4">
                <div className="mb-3 text-sm font-black text-commerce-ink">Alias / 스펙 기준 보정</div>
                <div className="grid grid-cols-2 gap-3">
                  <FormSelect label="카테고리" value={aliasReviewDraft.category} options={CATEGORIES} onChange={(value) => setAliasReviewDraft({ ...aliasReviewDraft, category: value })} />
                  <FormInput label="대상 필드" value={aliasReviewDraft.targetField} onChange={(value) => setAliasReviewDraft({ ...aliasReviewDraft, targetField: value })} />
                  <FormInput label="alias text" value={aliasReviewDraft.aliasText} onChange={(value) => setAliasReviewDraft({ ...aliasReviewDraft, aliasText: value })} />
                  <FormInput label="canonical value" value={aliasReviewDraft.canonicalValue} onChange={(value) => setAliasReviewDraft({ ...aliasReviewDraft, canonicalValue: value })} />
                </div>
                <textarea value={aliasReviewDraft.note} onChange={(event) => setAliasReviewDraft({ ...aliasReviewDraft, note: event.target.value })} placeholder="처리 메모" className="mt-3 h-24 w-full rounded border border-slate-300 px-3 py-2 text-xs" />
                <div className="mt-4 flex flex-wrap gap-2">
                  <button type="button" disabled={!selectedAliasReviewId || intakePending} onClick={resolveAliasReview} className="rounded bg-brand-blue px-3 py-2 text-xs font-black text-white disabled:opacity-50">선택 항목 해결</button>
                  <button type="button" disabled={!selectedAliasReviewId || intakePending} onClick={ignoreAliasReview} className="rounded border border-slate-300 px-3 py-2 text-xs font-black text-slate-700 disabled:opacity-50">무시</button>
                  <button type="button" disabled={intakePending} onClick={createAliasRule} className="rounded border border-brand-blue px-3 py-2 text-xs font-black text-brand-blue disabled:opacity-50">규칙만 추가</button>
                </div>
                <StateMessage type="info" title="운영 기준" body="이 큐는 AI 엔진이 불확실한 alias와 스펙 누락을 운영자가 보정하기 위한 내부 backlog입니다. 사용자 추천은 저장 API를 직접 실행하지 않습니다." />
              </div>
            </div>
          ) : null}
            </>
          ) : null}
        </Panel>
      </div>

      <div className="mt-5">
        <Panel title="가격 데이터 기준">
          <StateMessage type="info" title="운영 기준" body="대표 가격은 parts.price, 수동 보정 이력은 price_snapshots.source=ADMIN_MANUAL, 외부 구매처는 part_external_offers에 분리 저장됩니다." />
          <Link to="/admin/price-jobs" className="mt-5 inline-block rounded bg-brand-blue px-4 py-3 text-center text-sm font-bold text-white">가격 작업 보기</Link>
        </Panel>
      </div>
    </AdminShell>
  );
}

function adminPartRows(parts: AdminPart[], selectPart: (partId: string) => void) {
  return parts.map((part) => ({
    category: part.category,
    name: (
      <button type="button" onClick={() => selectPart(part.id)} className="text-left font-bold text-brand-blue hover:underline">
        {part.name}
      </button>
    ),
    manufacturer: part.manufacturer ?? '-',
    price: `${part.price.toLocaleString()}원`,
    status: <StatusBadge status={part.deletedAt ? 'DELETED' : part.status} />,
    tool: part.toolReady ? 'READY' : `누락 ${part.missingRequiredFields?.length ?? 0}`,
    updated: part.updatedAt ? new Date(part.updatedAt).toLocaleDateString('ko-KR') : '-'
  }));
}

function SectionToggleButton({ expanded, label, onClick }: { expanded: boolean; label: string; onClick: () => void }) {
  return (
    <button
      type="button"
      aria-expanded={expanded}
      aria-label={`${label} ${expanded ? '접기' : '펼치기'}`}
      onClick={onClick}
      className="rounded border border-slate-300 px-3 py-2 text-xs font-black text-slate-700 hover:border-brand-blue hover:text-brand-blue"
    >
      {expanded ? '접기' : '펼치기'}
    </button>
  );
}

function MetricCard({ label, value, tone = 'neutral' }: { label: string; value: number; tone?: 'neutral' | 'ok' | 'warn' }) {
  const toneClass = tone === 'warn'
    ? 'border-amber-200 bg-amber-50 text-amber-800'
    : tone === 'ok'
      ? 'border-emerald-200 bg-emerald-50 text-emerald-800'
      : 'border-slate-200 bg-white text-commerce-ink';
  return (
    <div className={`rounded-md border px-4 py-3 ${toneClass}`}>
      <div className="text-[11px] font-black text-slate-500">{label}</div>
      <div className="mt-2 text-xl font-black">{value.toLocaleString()}</div>
    </div>
  );
}

function qualityReportCategoryRows(categories: PartQualityReportCategory[]) {
  return categories.map((category) => ({
    category: category.category,
    active: category.activeParts.toLocaleString(),
    tool: qualityCount(category.toolReadyMissing),
    spec: qualityCount(category.requiredSpecMissing),
    benchmark: qualityCount(category.benchmarkMissing),
    fps: qualityCount(category.fpsCoverageGap),
    alias: qualityCount(category.aliasReviewOpen)
  }));
}

function qualityReportActionRows(items: PartQualityReportActionItem[]) {
  return items.map((item) => ({
    type: <StatusBadge status={item.type} />,
    category: item.category ?? '-',
    label: item.label ?? item.partId ?? item.id ?? '-',
    message: item.message ?? '-',
    'field/source': [item.targetField, item.sourceType, item.priority].filter(Boolean).join(' / ') || '-'
  }));
}

function qualityCount(value: number) {
  return value > 0 ? <span className="font-black text-amber-700">{value.toLocaleString()}</span> : <span className="font-black text-emerald-700">0</span>;
}

function sourceRows(
  sources: ManufacturerSource[],
  onSelect: (sourceId: string) => void,
  onScan: (sourceId: string) => void,
  pending: boolean
) {
  return sources.map((source) => ({
    manufacturer: (
      <button type="button" onClick={() => onSelect(source.id)} className="text-left font-bold text-brand-blue hover:underline">
        {source.manufacturer}
      </button>
    ),
    scope: source.categoryScope,
    type: source.sourceType,
    enabled: source.enabled ? 'ON' : '수동',
    status: <StatusBadge status={source.deletedAt ? 'DELETED' : source.status} />,
    source: <a className="font-bold text-brand-blue hover:underline" href={source.sourceUrl} target="_blank" rel="noreferrer">열기</a>,
    lastScan: source.lastCheckedAt ? new Date(source.lastCheckedAt).toLocaleString('ko-KR') : '-',
    action: (
      <button type="button" disabled={pending} onClick={() => onScan(source.id)} className="rounded bg-commerce-ink px-3 py-2 text-xs font-black text-white disabled:opacity-50">
        {pending ? 'scan 중' : 'scan'}
      </button>
    )
  }));
}

function postRows(
  posts: ManufacturerPost[],
  onSelect: (postId: string) => void,
  onCreateCandidate: (postId: string) => void,
  onCreateAiAssetDraft: (postId: string) => void,
  pending: boolean
) {
  return posts.map((post) => {
    const canCreateCandidate = post.classificationStatus === 'PRODUCT_CANDIDATE'
      && Boolean(post.detectedCategory)
      && Boolean(post.detectedProductName)
      && !post.catalogCandidateId;
    return {
      title: (
        <div className="min-w-[280px]">
          <button type="button" onClick={() => onSelect(post.id)} className="text-left font-bold text-brand-blue hover:underline">{post.title}</button>
          <div className="mt-1">
            <a className="text-[11px] text-slate-500 hover:underline" href={post.externalUrl} target="_blank" rel="noreferrer">공식 글 열기</a>
          </div>
        </div>
      ),
      status: <StatusBadge status={post.deletedAt ? 'DELETED' : post.classificationStatus} />,
      category: post.detectedCategory ?? '-',
      candidate: post.catalogCandidateId ? '연결됨' : '-',
      detectedAt: post.createdAt ? new Date(post.createdAt).toLocaleString('ko-KR') : '-',
      action: (
        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            disabled={pending || !canCreateCandidate}
            title={canCreateCandidate ? '네이버 검색 후보를 생성합니다.' : 'PENDING 게시글은 AI 초안화를 먼저 실행하십시오.'}
            onClick={() => onCreateCandidate(post.id)}
            className="rounded border border-brand-blue px-3 py-2 text-xs font-black text-brand-blue disabled:opacity-50"
          >
            후보 생성
          </button>
          <button type="button" disabled={pending} onClick={() => onCreateAiAssetDraft(post.id)} className="rounded bg-commerce-ink px-3 py-2 text-xs font-black text-white disabled:opacity-50">
            AI 초안화
          </button>
        </div>
      )
    };
  });
}

function candidateRows(
  candidates: PartCatalogCandidate[],
  onSelect: (candidateId: string) => void,
  onApprove: (candidateId: string) => void,
  onReject: (candidateId: string) => void,
  onRefreshOffers: (candidateId: string) => void,
  onOpenPartDraft: (partId: string) => void,
  pending: boolean
) {
  return candidates.map((candidate) => {
    const canReview = candidate.candidateStatus === 'DISCOVERED';
    const hasDraft = Boolean(candidate.publishedPartId);
    return {
      category: candidate.category,
      title: (
        <div className="min-w-[260px]">
          <button type="button" onClick={() => onSelect(candidate.id)} className="text-left font-bold text-brand-blue hover:underline">{candidate.title}</button>
          <div className="mt-1 text-[11px] text-slate-500">{candidate.searchQuery}</div>
        </div>
      ),
      price: candidate.lowPrice ? `${candidate.lowPrice.toLocaleString()}원` : '-',
      supplier: candidate.supplierName ?? '-',
      status: <StatusBadge status={candidate.deletedAt ? 'DELETED' : candidate.candidateStatus} />,
      part: candidate.publishedPartId ? `${candidate.publishedPartStatus ?? 'PART'} 초안` : '-',
      action: hasDraft ? (
        <button type="button" disabled={pending} onClick={() => candidate.publishedPartId && onOpenPartDraft(candidate.publishedPartId)} className="rounded bg-emerald-600 px-3 py-2 text-xs font-black text-white disabled:opacity-50">초안 열기</button>
      ) : canReview ? (
        <div className="flex gap-2">
          <button type="button" disabled={pending} onClick={() => onRefreshOffers(candidate.id)} className="rounded border border-brand-blue px-3 py-2 text-xs font-black text-brand-blue disabled:opacity-50">offer 재검색</button>
          <button type="button" disabled={pending} onClick={() => onApprove(candidate.id)} className="rounded bg-brand-blue px-3 py-2 text-xs font-black text-white disabled:opacity-50">승인</button>
          <button type="button" disabled={pending} onClick={() => onReject(candidate.id)} className="rounded border border-slate-300 px-3 py-2 text-xs font-black text-slate-700 disabled:opacity-50">거절</button>
        </div>
      ) : '-'
    };
  });
}

function aliasReviewRows(items: PartAliasReviewItem[], onSelect: (itemId: string) => void) {
  return items.map((item) => ({
    category: item.category ?? '-',
    field: item.targetField ?? '-',
    alias: (
      <button type="button" onClick={() => onSelect(item.id)} className="text-left font-bold text-brand-blue hover:underline">
        {item.aliasText ?? item.partName ?? '-'}
      </button>
    ),
    raw: item.rawValue ?? '-',
    source: item.sourceType,
    status: <StatusBadge status={item.status} />,
    created: item.createdAt ? new Date(item.createdAt).toLocaleString('ko-KR') : '-'
  }));
}

function aliasRuleRows(rules: PartAliasRule[]) {
  return rules.map((rule) => ({
    category: rule.category,
    field: rule.targetField,
    alias: rule.aliasText,
    canonical: rule.canonicalValue,
    source: rule.source ?? '-'
  }));
}

function sourceToDraft(source: ManufacturerSource): SourceDraft {
  return {
    manufacturer: source.manufacturer,
    categoryScope: source.categoryScope,
    sourceType: source.sourceType,
    sourceUrl: source.sourceUrl,
    enabled: source.enabled,
    pollIntervalMinutes: String(source.pollIntervalMinutes ?? 1440),
    status: source.status,
    parserConfigText: prettyJson(source.parserConfig ?? {})
  };
}

function postToDraft(post: ManufacturerPost): PostDraft {
  return {
    sourceId: post.sourceId ?? '',
    externalUrl: post.externalUrl,
    title: post.title,
    publishedAt: post.publishedAt ?? '',
    excerpt: post.excerpt ?? '',
    classificationStatus: post.classificationStatus,
    detectedCategory: post.detectedCategory ?? '',
    detectedProductName: post.detectedProductName ?? '',
    confidence: post.confidence == null ? '' : String(post.confidence)
  };
}

function candidateToDraft(candidate: PartCatalogCandidate): CandidateDraft {
  return {
    category: candidate.category,
    searchQuery: candidate.searchQuery,
    title: candidate.title,
    manufacturerGuess: candidate.manufacturerGuess ?? '',
    imageUrl: candidate.imageUrl ?? '',
    supplierName: candidate.supplierName ?? '',
    offerUrl: candidate.offerUrl ?? '',
    lowPrice: candidate.lowPrice == null ? '' : String(candidate.lowPrice)
  };
}

function partToDraft(part: AdminPart): PartDraft {
  return {
    category: part.category,
    name: part.name,
    manufacturer: part.manufacturer ?? '',
    price: String(part.price ?? 0),
    status: part.deletedAt ? 'INACTIVE' : part.status,
    attributes: { ...(part.attributes ?? {}) }
  };
}

function partToOfferDraft(part: AdminPart): OfferDraft {
  const offer = part.externalOffer;
  return {
    searchQuery: '',
    title: offer?.title ?? '',
    imageUrl: offer?.imageUrl ?? '',
    supplierName: offer?.supplierName ?? '',
    offerUrl: offer?.offerUrl ?? '',
    lowPrice: offer?.lowPrice == null ? '' : String(offer.lowPrice)
  };
}

function draftToPayload(draft: PartDraft): AdminPartPayload {
  return {
    category: draft.category,
    name: draft.name.trim(),
    manufacturer: emptyToNull(draft.manufacturer),
    price: Number(draft.price || 0),
    status: draft.status,
    attributes: cleanAttributes(draft.attributes)
  };
}

function cleanAttributes(attributes: Record<string, unknown>) {
  const result: Record<string, unknown> = {};
  Object.entries(attributes).forEach(([key, value]) => {
    if (value === '' || value === null || value === undefined) {
      return;
    }
    result[key] = value;
  });
  return result;
}

function SpecFieldControl({ field, value, onChange }: { field: SpecField; value: unknown; onChange: (value: unknown) => void }) {
  if (field.type === 'boolean') {
    return (
      <label className="flex items-center gap-2 rounded border border-slate-200 px-3 py-2 text-xs font-bold text-slate-700">
        <input type="checkbox" checked={Boolean(value)} onChange={(event) => onChange(event.target.checked)} />
        {field.label}
      </label>
    );
  }
  const textValue = Array.isArray(value) ? value.join(', ') : value == null ? '' : String(value);
  return (
    <label className="text-xs font-bold text-slate-600">
      {field.label}
      <input
        value={textValue}
        onChange={(event) => onChange(field.type === 'list' ? splitList(event.target.value) : event.target.value)}
        className="mt-1 w-full rounded border border-slate-300 px-3 py-2 text-xs"
      />
    </label>
  );
}

function FormInput({ label, value, onChange }: { label: string; value: string; onChange: (value: string) => void }) {
  return (
    <label className="block text-xs font-bold text-slate-600">
      {label}
      <input value={value} onChange={(event) => onChange(event.target.value)} className="mt-1 w-full rounded border border-slate-300 px-3 py-2 text-xs" />
    </label>
  );
}

function FormSelect({ label, value, options, onChange }: { label: string; value: string; options: string[]; onChange: (value: string) => void }) {
  return (
    <label className="block text-xs font-bold text-slate-600">
      {label}
      <select value={value} onChange={(event) => onChange(event.target.value)} className="mt-1 w-full rounded border border-slate-300 px-3 py-2 text-xs">
        {options.map((option) => <option key={option} value={option}>{option}</option>)}
      </select>
    </label>
  );
}

function prettyJson(value: unknown) {
  if (typeof value === 'string') {
    try {
      return JSON.stringify(JSON.parse(value), null, 2);
    } catch {
      return value;
    }
  }
  return JSON.stringify(value ?? {}, null, 2);
}

function splitList(value: string) {
  return value.split(',').map((item) => item.trim()).filter(Boolean);
}

function emptyToNull(value: string) {
  return value.trim() ? value.trim() : null;
}

function numberOrUndefined(value: string) {
  if (!value.trim()) {
    return undefined;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}

function numberOrNull(value: string) {
  if (!value.trim()) {
    return null;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}
