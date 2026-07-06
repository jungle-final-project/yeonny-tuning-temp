export type TicketRow = {
  id: string;
  user: string;
  symptom: string;
  status: string;
  cause: string;
  confidence: string;
};

export type AgentLogUploadDto = {
  id: string;
  status: string;
  fileName: string;
  fileSize?: number;
  rangeMinutes: number;
  summary?: string;
  asRagAnalysis?: AsRagAnalysisDto | null;
  safetyAdviceLevel?: string | null;
  safetyNotices?: SafetyNoticeDto[] | null;
  createdAt?: string;
  deleteAfter: string;
};

export type AsRagAnalysisDto = {
  analysisVersion?: string;
  retrievalMode?: string;
  recommendedService?: string;
  recommendedServiceLabel?: string;
  supportDecision?: string;
  supportDecisionLabel?: string;
  recommendationMessage?: string;
  confidence?: string;
  summaryText?: string;
  evidence?: Record<string, unknown>[];
  supportRouting?: Record<string, unknown>;
};

export type SafetyNoticeDto = {
  code?: string;
  message?: string;
};

export type AgentActivationTokenDto = {
  id: string;
  activationToken: string;
  tokenType: string;
  expiresAt?: string;
};

export type AsTicketDraftDto = {
  draftId: string;
  logUploadId: string;
  title: string;
  detailDescription: string;
  symptomType: string;
  symptom: string;
  detectedAt: string;
  incidentWindow: {
    startedAt?: string;
    endedAt?: string;
    detectedAt?: string;
    symptomType?: string;
  };
  supportRequestKind: string;
  status: string;
  createdAt?: string;
};

export type CauseCandidate = {
  code?: string;
  label?: string;
  confidence?: string;
  reason?: string;
  evidenceIds?: string[];
};

export type UpgradeCandidate = {
  category?: string;
  reason?: string;
  partIds?: string[];
  estimatedPrice?: number;
};

export type AsTicketDto = {
  id: string;
  status: string;
  analysisStatus?: string | null;
  reviewStatus?: string | null;
  supportDecision?: string | null;
  riskLevel?: string | null;
  autoResponseAllowed?: boolean | null;
  safetyAdviceLevel?: string | null;
  safetyNotices?: SafetyNoticeDto[] | null;
  feedbackRating?: number | null;
  feedbackComment?: string | null;
  feedbackCreatedAt?: string | null;
  diagnosticAccuracy?: string | null;
  symptom: string;
  logUploadId?: string | null;
  supportChatRoomId?: string | null;
  supportChatUserUnreadCount?: number;
  supportChatAdminUnreadCount?: number;
  supportChatLastMessageAt?: string | null;
  assignedAdminId?: string | null;
  causeCandidates: CauseCandidate[];
  upgradeCandidates: UpgradeCandidate[];
  adminNote?: string | null;
  remoteSupportLink?: string | null;
  remoteSupportStatus?: string | null;
  visitSupportRequired?: boolean | null;
  visitSupportStatus?: string | null;
  visitPreferredDate?: string | null;
  visitTimeSlot?: string | null;
  resolvedAt?: string | null;
  createdAt?: string;
};

export type SupportChatContact = {
  id: string;
  asTicketId: string;
  status: string;
  ticketStatus?: string;
  title: string;
  symptom?: string;
  lastMessagePreview?: string | null;
  lastMessageAt?: string | null;
  userUnreadCount?: number;
  adminUnreadCount?: number;
  assignedAdminId?: string | null;
  canSendMessage?: boolean;
  visitReservation?: VisitSupportReservation | null;
  user?: {
    id?: string;
    email?: string;
    name?: string;
  };
};

export type VisitSupportReservation = {
  id: string;
  status: 'REQUESTED' | 'RESCHEDULE_REQUESTED' | 'SCHEDULED' | 'VISIT_IN_PROGRESS' | 'COMPLETED' | 'CANCELLED' | string;
  scheduledAt?: string | null;
  addressSnapshot?: string | null;
  technicianNote?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
};

export type SupportChatMessage = {
  id: string;
  role: 'USER' | 'ADMIN' | 'SYSTEM';
  content: string;
  senderId?: string | null;
  senderName?: string | null;
  createdAt?: string;
};

export type SupportChatSessionDto = {
  contact: SupportChatContact | null;
  messages: SupportChatMessage[];
  supportNewPath?: string;
  pollingIntervalMs?: number;
};

export type SupportChatSessionListDto = {
  items: SupportChatContact[];
  pollingIntervalMs?: number;
};
