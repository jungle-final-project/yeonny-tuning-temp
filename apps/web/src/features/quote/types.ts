export type RequirementQuestion = {
  key: string;
  label: string;
  options: string[];
  required: boolean;
};

export type ParsedRequirement = {
  id: string;
  rawMessage: string;
  budget?: number | null;
  usageTags: string[];
  parsedContext: Record<string, unknown>;
  questions: RequirementQuestion[];
  agentSessionId?: string | null;
  agentSummary?: string | null;
  evidenceIds?: string[];
};

export type WarningDto = {
  code?: string;
  message?: string;
  severity?: string;
};

export type BuildItem = {
  id?: string;
  partId?: string;
  category: string;
  name: string;
  manufacturer?: string | null;
  price: number;
  status?: string;
  attributes?: Record<string, unknown>;
};

export type ToolResult = {
  tool: string;
  status: string;
  confidence: string;
  summary: string;
  details?: Record<string, unknown>;
};

export type BuildSummary = {
  id: string;
  name: string;
  recommendedFor?: string;
  summary?: string;
  totalPrice: number;
  confidence: string;
  items: BuildItem[];
  warnings: WarningDto[];
  evidenceIds?: string[];
  agentSessionId?: string | null;
  agentSummary?: string | null;
  changeableCategories?: string[];
  createdAt?: string;
  toolResults?: ToolResult[];
};

export type RecommendBuildResponse = {
  agentSessionId?: string | null;
  recommendations: BuildSummary[];
  warnings: WarningDto[];
  evidenceIds: string[];
  toolResults?: ToolResult[];
};

export type ChangePartDiffRow = {
  label: string;
  before: string;
  after: string;
  diff: string;
  status: string;
};

export type ChangePartResponse = {
  buildId: string;
  category: string;
  previousPartId?: string | null;
  selectedPartId: string;
  totalPrice: number;
  beforeBuild: BuildSummary;
  afterBuild: Pick<BuildSummary, 'id' | 'name' | 'totalPrice' | 'items'>;
  diffRows: ChangePartDiffRow[];
  toolResults: ToolResult[];
  agentSessionId?: string | null;
  agentSummary?: string | null;
  warnings: WarningDto[];
};

export type ParseRequirementPayload = {
  message: string;
  budget?: number;
  usageTags?: string[];
  resolution?: string;
  preferredVendors?: string[];
  priority?: string;
};
