import { loadTossPayments } from '@tosspayments/tosspayments-sdk';
import type { TossPaymentsWidgets } from '@tosspayments/tosspayments-sdk';

const TOSS_DOCS_TEST_CLIENT_KEY = 'test_gck_docs_Ovk5rk1EwkEbP0W43n07xlzm';
const CUSTOMER_KEY_STORAGE_KEY = 'toss-point-payment-customer-key';

let widgetsPromise: Promise<TossPaymentsWidgets> | null = null;

type OpenTossPointPaymentWindowOptions = {
  amount: number;
  orderId: string;
  orderName: string;
  onPaymentRequestError: (error: unknown) => void;
};

export async function openTossPointPaymentWindow({
  amount,
  orderId,
  orderName,
  onPaymentRequestError
}: OpenTossPointPaymentWindowOptions) {
  const widgets = await getWidgets();
  await widgets.setAmount({ currency: 'KRW', value: amount });

  const paymentWindow = await widgets.renderPaymentWindow({
    variantKey: { paymentMethod: 'DEFAULT', agreement: 'AGREEMENT' }
  });
  let submitted = false;

  paymentWindow.on('paymentRequest', async () => {
    if (submitted) return;
    submitted = true;

    try {
      await widgets.requestPayment({
        orderId,
        orderName,
        successUrl: `${window.location.origin}/checkout/toss/success/${orderId}`,
        failUrl: `${window.location.origin}/checkout/toss/fail/${orderId}`
      });
    } catch (error) {
      await paymentWindow.destroy();
      onPaymentRequestError(error);
    }
  });
}

async function getWidgets() {
  if (!widgetsPromise) {
    widgetsPromise = loadTossPayments(clientKey()).then((tossPayments) => (
      tossPayments.widgets({ customerKey: customerKey() })
    ));
  }
  return widgetsPromise;
}

function clientKey() {
  return import.meta.env.VITE_TOSS_WIDGET_CLIENT_KEY?.trim() || TOSS_DOCS_TEST_CLIENT_KEY;
}

function customerKey() {
  const stored = window.sessionStorage.getItem(CUSTOMER_KEY_STORAGE_KEY);
  if (stored) return stored;

  const generated = crypto.randomUUID();
  window.sessionStorage.setItem(CUSTOMER_KEY_STORAGE_KEY, generated);
  return generated;
}
