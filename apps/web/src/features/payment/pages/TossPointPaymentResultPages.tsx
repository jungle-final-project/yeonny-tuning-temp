import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CheckCircle2, XCircle } from 'lucide-react';
import { useEffect, useRef } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { Screen } from '../../../components/ui';
import { getAssemblyRequest } from '../../parts/assemblyApi';
import { payWithPoints } from '../paymentApi';

export function TossPointPaymentSuccessPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { requestId } = useParams();
  const [searchParams] = useSearchParams();
  const confirmationStarted = useRef(false);
  const requestQuery = useQuery({
    queryKey: ['assembly-request', requestId],
    queryFn: () => getAssemblyRequest(requestId!),
    enabled: Boolean(requestId)
  });
  const validationError = validateTossResult(
    requestId,
    requestQuery.data?.payment?.amount,
    searchParams
  );
  const pointPaymentMutation = useMutation({
    mutationFn: () => payWithPoints(requestId!, `point-toss-${requestId}`),
    onSuccess: async (result) => {
      queryClient.setQueryData(['point-wallet'], result.wallet);
      await queryClient.invalidateQueries({ queryKey: ['assembly-request', requestId] });
      navigate(`/checkout/complete/${requestId}`, { replace: true });
    }
  });

  useEffect(() => {
    if (!requestId || requestQuery.isLoading || requestQuery.isError || !requestQuery.data || validationError || confirmationStarted.current) return;
    if (requestQuery.data.payment?.status === 'PAID') {
      navigate(`/checkout/complete/${requestId}`, { replace: true });
      return;
    }

    confirmationStarted.current = true;
    pointPaymentMutation.mutate();
  }, [navigate, pointPaymentMutation, requestId, requestQuery.data, requestQuery.isError, requestQuery.isLoading, validationError]);

  if (!requestId) return <ResultState type="error" title="주문번호를 확인할 수 없습니다" body="결제 페이지에서 다시 시도해 주세요." />;
  if (requestQuery.isLoading) return <ResultState type="loading" title="토스 결제 결과 확인 중" body="결제 금액과 주문번호를 확인하고 있습니다." />;
  if (requestQuery.isError || !requestQuery.data) return <ResultState type="error" title="주문 정보를 불러오지 못했습니다" body="결제 페이지에서 다시 시도해 주세요." requestId={requestId} />;
  if (validationError) return <ResultState type="error" title="토스 결제 정보가 일치하지 않습니다" body={validationError} requestId={requestId} />;
  if (pointPaymentMutation.isError) return <ResultState type="error" title="포인트 결제에 실패했습니다" body={errorMessage(pointPaymentMutation.error)} requestId={requestId} />;

  return <ResultState type="loading" title="포인트 결제 처리 중" body="토스 테스트 인증을 확인했습니다. 실제 PG 승인 없이 보유 포인트를 차감하고 있습니다." />;
}

export function TossPointPaymentFailPage() {
  const { requestId } = useParams();
  const [searchParams] = useSearchParams();
  const message = searchParams.get('message') || '토스 결제가 취소되었거나 인증을 완료하지 못했습니다.';

  return <ResultState type="error" title="토스 결제가 완료되지 않았습니다" body={`${message} 포인트는 차감되지 않았습니다.`} requestId={requestId} />;
}

function validateTossResult(requestId: string | undefined, expectedAmount: number | undefined, searchParams: URLSearchParams) {
  if (!requestId) return '현재 주문번호가 없습니다.';
  if (expectedAmount === undefined) return '서버에 저장된 결제 금액을 확인할 수 없습니다.';
  if (!searchParams.get('paymentKey')) return '토스 결제 식별값이 없습니다.';
  if (searchParams.get('orderId') !== requestId) return '토스 주문번호와 현재 주문번호가 다릅니다.';

  const amount = Number(searchParams.get('amount'));
  if (!Number.isSafeInteger(amount) || amount !== expectedAmount) return '토스 결제 금액과 서버에 저장된 결제 금액이 다릅니다.';
  return null;
}

function ResultState({ type, title, body, requestId }: { type: 'loading' | 'error'; title: string; body: string; requestId?: string }) {
  const Icon = type === 'loading' ? CheckCircle2 : XCircle;
  return (
    <Screen>
      <section className="mx-auto max-w-xl rounded-lg border border-commerce-line bg-white p-8 text-center shadow-product">
        <div className={`mx-auto grid h-14 w-14 place-items-center rounded-full ${type === 'loading' ? 'bg-blue-50 text-brand-blue' : 'bg-red-50 text-red-600'}`}><Icon size={28} /></div>
        <h1 className="mt-4 text-2xl font-black text-commerce-ink">{title}</h1>
        <p className="mt-2 break-keep text-sm font-bold leading-6 text-slate-600">{body}</p>
        {requestId ? <Link to={`/checkout/payment/${requestId}`} className="mt-5 inline-flex min-h-11 items-center justify-center rounded-md bg-commerce-ink px-5 text-sm font-black text-white">결제 페이지로 돌아가기</Link> : null}
      </section>
    </Screen>
  );
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '요청을 처리하지 못했습니다.';
}
