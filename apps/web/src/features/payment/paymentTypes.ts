export type PaymentStatus = 'PENDING' | 'PAID' | 'CANCELLED' | 'REFUNDED';

export type PaymentAttemptStatus =
  | 'READY'
  | 'PROCESSING'
  | 'VERIFYING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'EXPIRED';

export type PaymentCheckoutMethod = 'POINT' | 'CARD' | 'KAKAOPAY' | 'TOSSPAY';
export type MockPaymentResult = 'SUCCESS' | 'FAILURE' | 'CANCEL';

export type PaymentAttempt = {
  id: string;
  provider: 'MOCK' | 'PORTONE_V2' | 'BUILDGRAPH_POINT';
  merchantPaymentId: string;
  providerTransactionId?: string | null;
  pgTransactionId?: string | null;
  payMethod: 'POINT' | 'CARD' | 'EASY_PAY';
  easyPayProvider?: 'KAKAOPAY' | 'TOSSPAY' | null;
  requestedAmount: number;
  approvedAmount?: number | null;
  currency: string;
  status: PaymentAttemptStatus;
  failureCode?: string | null;
  failureMessage?: string | null;
  expiresAt: string;
  verifiedAt?: string | null;
  completedAt?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
  checkout?: {
    provider: 'MOCK';
    providerTransactionId: string;
    merchantPaymentId: string;
    orderName: string;
    amount: number;
    currency: string;
    payMethod: 'CARD' | 'EASY_PAY';
    easyPayProvider?: 'KAKAOPAY' | 'TOSSPAY' | null;
    expiresAt: string;
  };
};

export type PaymentSummary = {
  id: string;
  amount: number;
  paidAmount: number;
  currency: string;
  provider: 'LEGACY_VIRTUAL' | 'MOCK' | 'PORTONE_V2' | 'BUILDGRAPH_POINT';
  method: 'VIRTUAL' | 'POINT' | 'CARD' | 'EASY_PAY';
  status: PaymentStatus;
  paidAt?: string | null;
  verifiedAt?: string | null;
  refundedAt?: string | null;
  latestAttempt?: PaymentAttempt | null;
  updatedAt?: string | null;
};

export type PointWallet = {
  id: string;
  name: string;
  balance: number;
  pointValueWon: number;
  currency: 'KRW';
  updatedAt?: string | null;
};

export type PointPaymentResult = {
  attempt: PaymentAttempt;
  wallet: PointWallet;
};
