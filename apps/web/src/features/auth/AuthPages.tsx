import { FormEvent, ReactNode, useEffect, useState } from 'react';
import { Link, useNavigate, useSearchParams, type NavigateFunction } from 'react-router-dom';
import { LogIn, ShieldCheck, UserPlus } from 'lucide-react';
import { Screen, StateMessage } from '../../components/ui';
import { ApiError, clearToken, saveAuthTokens } from '../../lib/api';
import { exchangeAuthCode, googleOAuthStartUrl, login, signup, type LoginResponse } from './authApi';

const initialExchangeByCode = new Map<string, Promise<LoginResponse>>();

function safeRedirectPath(raw: string | null) {
  return raw && raw.startsWith('/') && !raw.startsWith('//') ? raw : '/';
}

function beginGoogleOAuth(redirect: string) {
  window.location.href = googleOAuthStartUrl(safeRedirectPath(redirect));
}

function exchangeInitialAuthCode(code: string) {
  if (!initialExchangeByCode.has(code)) {
    initialExchangeByCode.set(code, exchangeAuthCode({ code }));
  }
  return initialExchangeByCode.get(code)!;
}

export function LoginPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const redirect = safeRedirectPath(searchParams.get('redirect'));

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    setSubmitting(true);
    const form = new FormData(event.currentTarget);
    try {
      const email = String(form.get('email') ?? '').trim();
      const password = String(form.get('password') ?? '');
      const response = await login(email, password);
      saveAuthTokens(response.accessToken, response.refreshToken, response.user);
      navigate(redirect);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : '서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요. (데모 계정: user@example.com / passw0rd!)');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthShell
      icon={<LogIn size={20} />}
      title="로그인"
      subtitle="이메일, 비밀번호 또는 Google 계정으로 로그인하세요."
      footer={<Link to="/signup" className="block h-11 rounded border border-slate-300 pt-3 text-center text-sm font-bold text-slate-700 transition hover:border-commerce-ink hover:text-commerce-ink">회원가입</Link>}
    >
      {error ? <StateMessage type="warn" title="로그인 실패" body={error} /> : null}
      <form onSubmit={submit} className="space-y-4">
        <EmailPasswordFields passwordAutocomplete="current-password" />
        <PrimaryButton disabled={submitting}>{submitting ? '로그인 중' : '로그인'}</PrimaryButton>
      </form>
      <AuthDivider />
      <GoogleButton onClick={() => beginGoogleOAuth(redirect)} label="Google로 계속하기" />
    </AuthShell>
  );
}

export function AdminLoginPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const redirect = safeRedirectPath(searchParams.get('redirect')) || '/admin';
  const adminRedirect = redirect.startsWith('/admin') ? redirect : '/admin';

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    setSubmitting(true);
    const form = new FormData(event.currentTarget);
    try {
      const email = String(form.get('email') ?? '').trim();
      const password = String(form.get('password') ?? '');
      const response = await login(email, password);
      if (response.user.role !== 'ADMIN') {
        clearToken();
        setError('관리자 권한이 있는 계정만 관리자 화면에 로그인할 수 있습니다.');
        return;
      }
      saveAuthTokens(response.accessToken, response.refreshToken, response.user);
      navigate(adminRedirect);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : '서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요. (관리자 데모 계정: admin@example.com / passw0rd!)');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthShell
      icon={<ShieldCheck size={20} />}
      title="관리자 로그인"
      subtitle="관리자 계정은 공개 회원가입으로 생성되지 않습니다."
      tone="admin"
      footer={<Link to="/login" className="block h-11 rounded border border-slate-300 pt-3 text-center text-sm font-bold text-slate-700 transition hover:border-commerce-ink hover:text-commerce-ink">일반 로그인으로 이동</Link>}
    >
      {error ? <StateMessage type="warn" title="관리자 로그인 실패" body={error} /> : null}
      <form onSubmit={submit} className="space-y-4">
        <EmailPasswordFields passwordAutocomplete="current-password" emailPlaceholder="admin@example.com" />
        <PrimaryButton disabled={submitting}>{submitting ? '확인 중' : '관리자 로그인'}</PrimaryButton>
      </form>
      <AuthDivider />
      <GoogleButton onClick={() => beginGoogleOAuth(adminRedirect)} label="관리자 Google 계정으로 계속하기" />
      <p className="text-xs leading-5 text-slate-500">
        Google 인증에 성공해도 서버에 이미 등록된 ADMIN 권한이 있어야 관리자 화면에 접근할 수 있습니다.
      </p>
    </AuthShell>
  );
}

export function SignupPage() {
  const navigate = useNavigate();
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    setSubmitting(true);
    const form = new FormData(event.currentTarget);
    try {
      const name = String(form.get('name') ?? '').trim();
      const email = String(form.get('email') ?? '').trim();
      const password = String(form.get('password') ?? '');
      const passwordConfirm = String(form.get('passwordConfirm') ?? '');
      const termsAccepted = form.get('termsAccepted') === 'on';
      const marketingAccepted = form.get('marketingAccepted') === 'on';

      if (password !== passwordConfirm) {
        setError('비밀번호 확인이 일치하지 않습니다.');
        return;
      }

      await signup({ name, email, password, termsAccepted, marketingAccepted });
      navigate('/login');
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : '서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthShell
      icon={<UserPlus size={20} />}
      title="회원가입"
      subtitle="가입한 계정은 일반 사용자 권한으로 시작합니다."
      wide
      footer={<Link to="/login" className="block h-11 rounded border border-slate-300 pt-3 text-center text-sm font-bold text-slate-700 transition hover:border-commerce-ink hover:text-commerce-ink">이미 계정이 있어요</Link>}
    >
      {error ? <StateMessage type="warn" title="회원가입 실패" body={error} /> : null}
      <form onSubmit={submit} className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <label className="block text-sm font-semibold text-slate-700">
          이름
          <input
            name="name"
            className="mt-2 h-11 w-full rounded border border-slate-300 px-3 text-sm"
            placeholder="홍길동"
            autoComplete="name"
            required
          />
        </label>
        <label className="block text-sm font-semibold text-slate-700">
          이메일
          <input
            name="email"
            className="mt-2 h-11 w-full rounded border border-slate-300 px-3 text-sm"
            placeholder="new-user@example.com"
            autoComplete="email"
            type="email"
            required
          />
        </label>
        <label className="block text-sm font-semibold text-slate-700">
          비밀번호
          <input
            name="password"
            className="mt-2 h-11 w-full rounded border border-slate-300 px-3 text-sm"
            placeholder="비밀번호"
            autoComplete="new-password"
            type="password"
            required
          />
        </label>
        <label className="block text-sm font-semibold text-slate-700">
          비밀번호 확인
          <input
            name="passwordConfirm"
            className="mt-2 h-11 w-full rounded border border-slate-300 px-3 text-sm"
            placeholder="비밀번호 확인"
            autoComplete="new-password"
            type="password"
            required
          />
        </label>
        <TermsFields className="sm:col-span-2" />
        <PrimaryButton className="sm:col-span-2" disabled={submitting}>{submitting ? '가입 중' : '회원가입'}</PrimaryButton>
      </form>
      <AuthDivider />
      <GoogleButton onClick={() => beginGoogleOAuth('/')} label="Google로 회원가입" />
      <p className="text-xs leading-5 text-slate-500">
        Google 신규 가입은 인증 후 별도 약관 동의 화면에서 완료됩니다.
      </p>
    </AuthShell>
  );
}

export function AuthCallbackPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const code = searchParams.get('code') ?? '';
  const redirect = safeRedirectPath(searchParams.get('redirect'));
  const [status, setStatus] = useState<'checking' | 'terms' | 'error'>('checking');
  const [error, setError] = useState('');
  const [pendingUser, setPendingUser] = useState<{ email?: string; name?: string }>({});
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    const oauthError = searchParams.get('error');
    if (oauthError) {
      setStatus('error');
      setError('Google 로그인 요청을 완료하지 못했습니다. 다시 시도해 주세요.');
      return;
    }
    if (!code) {
      setStatus('error');
      setError('로그인 코드가 만료되었거나 누락되었습니다.');
      return;
    }
    let cancelled = false;
    exchangeInitialAuthCode(code)
      .then((response) => {
        if (!cancelled) {
          finishLogin(response, redirect, navigate, setError, setStatus);
        }
      })
      .catch((cause) => {
        if (cancelled) return;
        if (cause instanceof ApiError && cause.status === 400 && cause.details?.reason === 'TERMS_REQUIRED') {
          setPendingUser({
            email: typeof cause.details.email === 'string' ? cause.details.email : undefined,
            name: typeof cause.details.name === 'string' ? cause.details.name : undefined
          });
          setStatus('terms');
          return;
        }
        setStatus('error');
        setError(cause instanceof ApiError ? cause.message : 'Google 로그인 세션이 만료되었습니다.');
      });
    return () => {
      cancelled = true;
    };
  }, [code, navigate, redirect, searchParams]);

  async function completeSignup(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    setSubmitting(true);
    const form = new FormData(event.currentTarget);
    try {
      const termsAccepted = form.get('termsAccepted') === 'on';
      const marketingAccepted = form.get('marketingAccepted') === 'on';
      const response = await exchangeAuthCode({ code, termsAccepted, marketingAccepted });
      finishLogin(response, redirect, navigate, setError, setStatus);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : 'Google 회원가입을 완료하지 못했습니다.');
    } finally {
      setSubmitting(false);
    }
  }

  if (status === 'terms') {
    return (
      <AuthShell
        icon={<UserPlus size={20} />}
        title="Google 회원가입 완료"
        subtitle={pendingUser.email ? `${pendingUser.email} 계정으로 가입을 완료합니다.` : '서비스 약관 동의 후 가입을 완료합니다.'}
      >
        {error ? <StateMessage type="warn" title="가입 완료 실패" body={error} /> : null}
        <form onSubmit={completeSignup} className="space-y-4">
          <TermsFields />
          <PrimaryButton disabled={submitting}>{submitting ? '가입 완료 중' : '가입 완료'}</PrimaryButton>
        </form>
      </AuthShell>
    );
  }

  return (
    <AuthShell
      icon={<LogIn size={20} />}
      title={status === 'checking' ? 'Google 로그인 확인 중' : 'Google 로그인 실패'}
      subtitle={status === 'checking' ? 'Google 인증 결과를 BuildGraph 계정과 연결하고 있습니다.' : '다시 로그인해 주세요.'}
    >
      {status === 'checking' ? <StateMessage type="info" title="로그인 처리 중" body="잠시만 기다려 주세요." /> : null}
      {status === 'error' ? <StateMessage type="warn" title="로그인 실패" body={error || 'Google 로그인에 실패했습니다.'} /> : null}
      {status === 'error' ? <Link to="/login" className="block h-11 rounded bg-brand-blue pt-3 text-center text-sm font-bold text-white">로그인으로 이동</Link> : null}
    </AuthShell>
  );
}

function finishLogin(
  response: LoginResponse,
  redirect: string,
  navigate: NavigateFunction,
  setError: (value: string) => void,
  setStatus: (value: 'checking' | 'terms' | 'error') => void
) {
  if (redirect.startsWith('/admin') && response.user.role !== 'ADMIN') {
    clearToken();
    setStatus('error');
    setError('관리자 권한이 있는 Google 계정만 관리자 화면에 접근할 수 있습니다.');
    return;
  }
  saveAuthTokens(response.accessToken, response.refreshToken, response.user);
  navigate(redirect);
}

function AuthShell({ icon, title, subtitle, children, footer, wide = false, tone = 'default' }: {
  icon: ReactNode;
  title: string;
  subtitle: string;
  children: ReactNode;
  footer?: ReactNode;
  wide?: boolean;
  tone?: 'default' | 'admin';
}) {
  const maxWidth = wide ? 'max-w-[560px]' : 'max-w-[440px]';
  return (
    <Screen>
      <div className={`mx-auto mt-10 w-full ${maxWidth} px-4 sm:mt-16 sm:px-0`}>
        <div className="panel p-6 sm:p-8">
          <div className="mb-6 flex items-start gap-3">
            <div className={`grid h-10 w-10 shrink-0 place-items-center rounded ${tone === 'admin' ? 'bg-slate-900 text-white' : 'bg-brand-pale text-brand-blue'}`}>
              {icon}
            </div>
            <div>
              <h1 className="text-xl font-black text-brand-navy">{title}</h1>
              <p className="mt-1 text-sm leading-6 text-slate-600">{subtitle}</p>
            </div>
          </div>
          <div className="space-y-4">{children}</div>
          {footer ? <div className="mt-4">{footer}</div> : null}
        </div>
      </div>
    </Screen>
  );
}

function EmailPasswordFields({ passwordAutocomplete, emailPlaceholder = 'user@example.com' }: { passwordAutocomplete: string; emailPlaceholder?: string }) {
  return (
    <>
      <label className="block text-sm font-semibold text-slate-700">
        이메일
        <input
          name="email"
          className="mt-2 h-11 w-full rounded border border-slate-300 px-3 text-sm"
          placeholder={emailPlaceholder}
          autoComplete="username"
          type="email"
          required
        />
      </label>
      <label className="block text-sm font-semibold text-slate-700">
        비밀번호
        <input
          name="password"
          className="mt-2 h-11 w-full rounded border border-slate-300 px-3 text-sm"
          placeholder="비밀번호"
          autoComplete={passwordAutocomplete}
          type="password"
          required
        />
      </label>
    </>
  );
}

function TermsFields({ className = '' }: { className?: string }) {
  return (
    <div className={`space-y-3 rounded border border-slate-200 bg-slate-50 p-4 ${className}`}>
      <label className="flex items-start gap-2 text-sm leading-6 text-slate-700">
        <input name="termsAccepted" className="mt-1" type="checkbox" required />
        <span>서비스 이용약관 및 로그 업로드 정책 확인, 개인정보 처리방침에 동의합니다.</span>
      </label>
      <label className="flex items-start gap-2 text-sm leading-6 text-slate-700">
        <input name="marketingAccepted" className="mt-1" type="checkbox" />
        <span>마케팅 정보 수신에 동의합니다.</span>
      </label>
    </div>
  );
}

function PrimaryButton({ children, disabled, className = '' }: { children: ReactNode; disabled?: boolean; className?: string }) {
  return (
    <button
      className={`h-11 w-full rounded bg-brand-blue text-sm font-bold text-white transition hover:bg-blue-700 disabled:cursor-not-allowed disabled:bg-slate-400 ${className}`}
      disabled={disabled}
    >
      {children}
    </button>
  );
}

function GoogleButton({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex h-11 w-full items-center justify-center gap-2 rounded border border-slate-300 bg-white text-sm font-bold text-slate-800 transition hover:border-commerce-ink hover:text-commerce-ink focus:outline-none focus:ring-4 focus:ring-blue-100"
    >
      <span className="grid h-5 w-5 place-items-center rounded-full border border-slate-200 text-xs font-black text-brand-blue">G</span>
      {label}
    </button>
  );
}

function AuthDivider() {
  return (
    <div className="flex items-center gap-3 text-xs font-bold text-slate-400">
      <span className="h-px flex-1 bg-slate-200" />
      또는
      <span className="h-px flex-1 bg-slate-200" />
    </div>
  );
}
