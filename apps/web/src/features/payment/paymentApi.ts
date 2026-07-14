import { api } from '../../lib/api';
import type {
  MockPaymentResult,
  PaymentAttempt,
  PaymentCheckoutMethod,
  PointPaymentResult,
  PointWallet
} from './paymentTypes';

export function createPaymentAttempt(requestId: string, method: PaymentCheckoutMethod, idempotencyKey: string) {
  return api<PaymentAttempt>(`/api/assembly-requests/${requestId}/payments/attempts`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({ method })
  });
}

export function setMockPaymentResult(attemptId: string, result: MockPaymentResult) {
  return api<PaymentAttempt>(`/api/payments/attempts/${attemptId}/mock-result`, {
    method: 'POST',
    body: JSON.stringify({ result })
  });
}

export function completePaymentAttempt(attemptId: string) {
  return api<PaymentAttempt>(`/api/payments/attempts/${attemptId}/complete`, { method: 'POST' });
}

export function getPointWallet() {
  return api<PointWallet>('/api/users/me/points');
}

export function payWithPoints(requestId: string, idempotencyKey: string) {
  return api<PointPaymentResult>(`/api/assembly-requests/${requestId}/payments/points/confirm`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey }
  });
}
