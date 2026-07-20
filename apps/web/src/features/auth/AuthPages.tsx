import { FormEvent, ReactNode, useEffect, useRef, useState, type Dispatch, type SetStateAction } from 'react';
import { Link, useNavigate, useSearchParams, type NavigateFunction } from 'react-router-dom';
import { LogIn, ShieldCheck, UserPlus, UserRound } from 'lucide-react';
import { Screen, StateMessage } from '../../components/ui';
import { ApiError, clearToken, saveAuthTokens, saveAuthUser } from '../../lib/api';
import { exchangeAuthCode, getCurrentUser, googleOAuthStartUrl, login, signup, updateCurrentUser, verifyProfilePassword, type CurrentUser, type LoginResponse } from './authApi';

const initialExchangeByCode = new Map<string, Promise<LoginResponse>>();
const KAKAO_POSTCODE_SCRIPT_ID = 'kakao-postcode-script';
const KAKAO_POSTCODE_SCRIPT_URL = 'https://t1.kakaocdn.net/mapjsapi/bundle/postcode/prod/postcode.v2.js';
const PROFILE_VERIFICATION_TOKEN_KEY = 'buildgraph.profileVerificationToken';

type KakaoPostcodeData = {
  zonecode: string;
  address: string;
  roadAddress: string;
  jibunAddress: string;
  userSelectedType: 'R' | 'J';
  bname: string;
  buildingName: string;
  apartment: string;
};

type KakaoPostcodeConstructor = new (options: {
  oncomplete: (data: KakaoPostcodeData) => void;
}) => {
  open: () => void;
};

declare global {
  interface Window {
    kakao?: {
      Postcode?: KakaoPostcodeConstructor;
    };
  }
}

let kakaoPostcodeScriptPromise: Promise<void> | null = null;

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
      footer={<Link to="/signup" className="block h-11 rounded border border-[#de6c2d] pt-3 text-center text-sm font-bold text-[#de6c2d] transition hover:bg-[#fff5ef] focus:outline-none focus:ring-4 focus:ring-[#f4c8b2]">회원가입</Link>}
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
      footer={<Link to="/login" className="block h-11 rounded border border-[#de6c2d] pt-3 text-center text-sm font-bold text-[#de6c2d] transition hover:bg-[#fff5ef] focus:outline-none focus:ring-4 focus:ring-[#f4c8b2]">일반 로그인으로 이동</Link>}
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
  const [contactErrors, setContactErrors] = useState<ContactFieldErrors>({});
  const [submitting, setSubmitting] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    setContactErrors({});
    setSubmitting(true);
    const form = new FormData(event.currentTarget);
    try {
      const name = String(form.get('name') ?? '').trim();
      const email = String(form.get('email') ?? '').trim();
      const password = String(form.get('password') ?? '');
      const passwordConfirm = String(form.get('passwordConfirm') ?? '');
      const contactAddress = readContactAddress(form);
      const termsAccepted = form.get('termsAccepted') === 'on';
      const marketingAccepted = form.get('marketingAccepted') === 'on';

      if (password !== passwordConfirm) {
        setError('비밀번호 확인이 일치하지 않습니다.');
        return;
      }

      await signup({ name, email, password, ...contactAddress, termsAccepted, marketingAccepted });
      navigate('/login');
    } catch (cause) {
      if (applyContactFieldErrors(cause, setContactErrors)) {
        return;
      }
      setError(cause instanceof Error ? cause.message : '서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.');
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
      footer={<Link to="/login" className="block h-11 rounded border border-[#de6c2d] pt-3 text-center text-sm font-bold text-[#de6c2d] transition hover:bg-[#fff5ef] focus:outline-none focus:ring-4 focus:ring-[#f4c8b2]">이미 계정이 있어요</Link>}
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
        <ContactAddressFields
          errors={contactErrors}
          onClearError={(field) => clearContactFieldError(setContactErrors, field)}
        />
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
  const [status, setStatus] = useState<'checking' | 'terms' | 'contact' | 'error'>('checking');
  const [error, setError] = useState('');
  const [pendingUser, setPendingUser] = useState<{ email?: string; name?: string }>({});
  const [contactErrors, setContactErrors] = useState<ContactFieldErrors>({});
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
        if (cause instanceof ApiError && cause.status === 400 && cause.details?.reason === 'CONTACT_REQUIRED') {
          setPendingUser({
            email: typeof cause.details.email === 'string' ? cause.details.email : undefined,
            name: typeof cause.details.name === 'string' ? cause.details.name : undefined
          });
          setStatus('contact');
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
    setContactErrors({});
    setSubmitting(true);
    const form = new FormData(event.currentTarget);
    try {
      const contactAddress = readContactAddress(form);
      const response = await exchangeAuthCode({
        code,
        ...contactAddress,
        ...(status === 'terms' ? {
          termsAccepted: form.get('termsAccepted') === 'on',
          marketingAccepted: form.get('marketingAccepted') === 'on'
        } : {})
      });
      finishLogin(response, redirect, navigate, setError, setStatus);
    } catch (cause) {
      if (applyContactFieldErrors(cause, setContactErrors)) {
        return;
      }
      setError(cause instanceof Error ? cause.message : 'Google 회원가입을 완료하지 못했습니다.');
    } finally {
      setSubmitting(false);
    }
  }

  if (status === 'terms' || status === 'contact') {
    const requiresTerms = status === 'terms';
    return (
      <AuthShell
        icon={<UserPlus size={20} />}
        title={requiresTerms ? 'Google 회원가입 완료' : '추가 정보 입력'}
        subtitle={pendingUser.email ? `${pendingUser.email} 계정의 연락처와 주소를 입력해 주세요.` : '서비스 이용에 필요한 연락처와 주소를 입력해 주세요.'}
        wide
      >
        {error ? <StateMessage type="warn" title="가입 완료 실패" body={error} /> : null}
        <form onSubmit={completeSignup} className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <ContactAddressFields
            errors={contactErrors}
            onClearError={(field) => clearContactFieldError(setContactErrors, field)}
          />
          {requiresTerms ? <TermsFields className="sm:col-span-2" /> : null}
          <PrimaryButton className="sm:col-span-2" disabled={submitting}>{submitting ? '저장 중' : '완료'}</PrimaryButton>
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
      {status === 'error' ? <Link to="/login" className="block h-11 rounded bg-[#de6c2d] pt-3 text-center text-sm font-bold text-white hover:bg-[#c45c22]">로그인으로 이동</Link> : null}
    </AuthShell>
  );
}

export function MyProfilePage() {
  const [profile, setProfile] = useState<CurrentUser | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [contactErrors, setContactErrors] = useState<ContactFieldErrors>({});
  const [submitting, setSubmitting] = useState(false);
  const [passwordVerified, setPasswordVerified] = useState(false);
  const [verifiedPassword, setVerifiedPassword] = useState('');
  const [googleVerificationToken, setGoogleVerificationToken] = useState('');

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    getCurrentUser()
      .then((currentUser) => {
        if (!cancelled) {
          setProfile(currentUser);
          setError('');
        }
      })
      .catch((cause) => {
        if (!cancelled) {
          setError(cause instanceof ApiError ? cause.message : '내 정보를 불러오지 못했습니다.');
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!profile || passwordVerified || !profile.authProviders?.includes('GOOGLE')) {
      return;
    }
    const token = sessionStorage.getItem(PROFILE_VERIFICATION_TOKEN_KEY);
    if (!token) {
      return;
    }
    setGoogleVerificationToken(token);
    setPasswordVerified(true);
    setSuccess('Google 본인 확인이 완료되었습니다.');
  }, [passwordVerified, profile]);

  async function verifyPassword(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    setSuccess('');
    setSubmitting(true);
    const form = new FormData(event.currentTarget);
    const password = String(form.get('password') ?? '');
    try {
      await verifyProfilePassword(password);
      setVerifiedPassword(password);
      setPasswordVerified(true);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : '비밀번호를 확인하지 못했습니다.');
    } finally {
      setSubmitting(false);
    }
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    setSuccess('');
    setContactErrors({});
    setSubmitting(true);
    const form = new FormData(event.currentTarget);
    try {
      const name = normalizeProfileName(String(form.get('name') ?? ''));
      const contactAddress = readContactAddress(form);
      const verificationPayload = googleVerificationToken
        ? { googleVerificationToken }
        : { currentPassword: verifiedPassword };
      const updatedUser = await updateCurrentUser({ ...verificationPayload, name, ...contactAddress });
      setProfile(updatedUser);
      saveAuthUser(updatedUser);
      if (googleVerificationToken) {
        sessionStorage.removeItem(PROFILE_VERIFICATION_TOKEN_KEY);
        setGoogleVerificationToken('');
      }
      setSuccess('내 정보가 저장되었습니다.');
    } catch (cause) {
      if (applyContactFieldErrors(cause, setContactErrors)) {
        return;
      }
      setError(cause instanceof Error ? cause.message : '내 정보를 저장하지 못했습니다.');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthShell
      icon={<UserRound size={20} />}
      title="마이페이지"
      subtitle="계정 정보와 AS 접수에 사용할 연락처를 관리합니다."
      wide
    >
      {loading ? <StateMessage type="info" title="내 정보 불러오는 중" body="잠시만 기다려 주세요." /> : null}
      {error ? <StateMessage type="warn" title="내 정보 처리 실패" body={error} /> : null}
      {success ? <StateMessage type="success" title="저장 완료" body={success} /> : null}
      {!loading && profile && !passwordVerified ? (
        <div className="space-y-4">
          {profile.authProviders?.includes('LOCAL') ? (
            <form onSubmit={verifyPassword} className="space-y-4">
              <label className="block text-sm font-semibold text-slate-700">
                비밀번호
                <input
                  name="password"
                  className="mt-2 h-11 w-full rounded border border-slate-300 px-3 text-sm focus:border-[#de6c2d] focus:outline-none focus:ring-4 focus:ring-[#f4c8b2]"
                  placeholder="현재 비밀번호"
                  autoComplete="current-password"
                  type="password"
                  required
                />
              </label>
              <PrimaryButton disabled={submitting}>{submitting ? '확인 중' : '확인'}</PrimaryButton>
            </form>
          ) : null}
          {profile.authProviders?.includes('GOOGLE') ? (
            <button
              type="button"
              onClick={() => beginGoogleOAuth('/my/profile?verified=google')}
              className="h-11 w-full rounded border border-slate-300 bg-white text-sm font-bold text-slate-700 transition hover:border-[#de6c2d] hover:text-[#de6c2d] focus:outline-none focus:ring-4 focus:ring-[#f4c8b2]"
            >
              Google로 본인 확인
            </button>
          ) : null}
          <p className="text-xs leading-5 text-slate-500">
            개인정보 보호를 위해 현재 비밀번호 또는 Google 본인 확인 후 수정 화면을 보여드립니다.
          </p>
        </div>
      ) : null}
      {!loading && profile && passwordVerified ? (
        <form key={`${profile.id}:${profile.name}:${profile.phoneNumber ?? ''}:${profile.postalCode ?? ''}:${profile.addressLine2 ?? ''}`} onSubmit={submit} className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <label className="block text-sm font-semibold text-slate-700">
            이름
            <input
              name="name"
              className="mt-2 h-11 w-full rounded border border-slate-300 px-3 text-sm focus:border-[#de6c2d] focus:outline-none focus:ring-4 focus:ring-[#f4c8b2]"
              defaultValue={profile.name}
              autoComplete="name"
              maxLength={100}
              required
            />
          </label>
          <label className="block text-sm font-semibold text-slate-700">
            이메일
            <input
              className="mt-2 h-11 w-full rounded border border-slate-300 bg-slate-50 px-3 text-sm text-slate-700"
              value={profile.email}
              readOnly
            />
          </label>
          <ContactAddressFields
            values={{
              phoneNumber: profile.phoneNumber ?? '',
              postalCode: profile.postalCode ?? '',
              addressLine1: profile.addressLine1 ?? '',
              addressLine2: profile.addressLine2 ?? ''
            }}
            errors={contactErrors}
            onClearError={(field) => clearContactFieldError(setContactErrors, field)}
          />
          <div className="flex flex-col gap-2 sm:col-span-2 sm:flex-row">
            <PrimaryButton className="sm:flex-1" disabled={submitting}>{submitting ? '저장 중' : '저장'}</PrimaryButton>
            <Link to="/" className="grid h-11 place-items-center rounded border border-slate-300 px-4 text-sm font-bold text-slate-700 transition hover:border-[#de6c2d] hover:text-[#de6c2d] focus:outline-none focus:ring-4 focus:ring-[#f4c8b2] sm:w-28">
              취소
            </Link>
          </div>
        </form>
      ) : null}
    </AuthShell>
  );
}

function finishLogin(
  response: LoginResponse,
  redirect: string,
  navigate: NavigateFunction,
  setError: (value: string) => void,
  setStatus: (value: 'checking' | 'terms' | 'contact' | 'error') => void
) {
  if (redirect.startsWith('/admin') && response.user.role !== 'ADMIN') {
    clearToken();
    setStatus('error');
    setError('관리자 권한이 있는 Google 계정만 관리자 화면에 접근할 수 있습니다.');
    return;
  }
  if (response.profileVerificationToken) {
    sessionStorage.setItem(PROFILE_VERIFICATION_TOKEN_KEY, response.profileVerificationToken);
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
            <div className={`grid h-10 w-10 shrink-0 place-items-center rounded ${tone === 'admin' ? 'bg-slate-900 text-white' : 'bg-[#fff5ef] text-[#de6c2d]'}`}>
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

type ContactField = keyof ContactAddressPayload;
type ContactFieldErrors = Partial<Record<ContactField, string>>;

function ContactAddressFields({
  values,
  errors = {},
  onClearError
}: {
  values?: Partial<ContactAddressPayload>;
  errors?: ContactFieldErrors;
  onClearError?: (field: ContactField) => void;
}) {
  const postalCodeRef = useRef<HTMLInputElement>(null);
  const addressLine1Ref = useRef<HTMLInputElement>(null);
  const addressLine2Ref = useRef<HTMLInputElement>(null);
  const [addressSearchError, setAddressSearchError] = useState('');
  const phoneError = errors.phoneNumber;
  const postalCodeError = errors.postalCode;
  const addressLine1Error = errors.addressLine1;
  const addressLine2Error = errors.addressLine2;

  async function openAddressSearch() {
    setAddressSearchError('');
    try {
      await loadKakaoPostcodeScript();
      const Postcode = window.kakao?.Postcode;
      if (!Postcode) {
        throw new Error('주소 검색 서비스를 불러오지 못했습니다.');
      }
      new Postcode({
        oncomplete(data) {
          const address = selectedAddressFromPostcode(data);
          if (postalCodeRef.current) {
            postalCodeRef.current.value = data.zonecode;
          }
          if (addressLine1Ref.current) {
            addressLine1Ref.current.value = address;
          }
          onClearError?.('postalCode');
          onClearError?.('addressLine1');
          addressLine2Ref.current?.focus();
        }
      }).open();
    } catch {
      setAddressSearchError('주소 검색 서비스를 불러오지 못했습니다. 네트워크 연결을 확인해 주세요.');
    }
  }

  return (
    <>
      <label className="block text-sm font-semibold text-slate-700">
        전화번호
        <input
          id="auth-phone-number"
          name="phoneNumber"
          className={`mt-2 h-11 w-full rounded border px-3 text-sm focus:outline-none focus:ring-4 ${
            phoneError
              ? 'border-red-500 bg-red-50 focus:border-red-500 focus:ring-red-100'
              : 'border-slate-300 focus:border-[#de6c2d] focus:ring-[#f4c8b2]'
          }`}
          placeholder="010-1234-5678"
          defaultValue={values?.phoneNumber ?? ''}
          autoComplete="tel"
          type="tel"
          maxLength={30}
          aria-invalid={Boolean(phoneError)}
          aria-describedby={phoneError ? 'auth-phone-number-error' : undefined}
          onChange={() => onClearError?.('phoneNumber')}
          required
        />
        {phoneError ? <FieldError id="auth-phone-number-error">{phoneError}</FieldError> : null}
      </label>
      <div className="block text-sm font-semibold text-slate-700">
        <label htmlFor="auth-postal-code">우편번호</label>
        <div className="mt-2 flex gap-2">
          <input
            id="auth-postal-code"
            ref={postalCodeRef}
            name="postalCode"
            className={`h-11 min-w-0 flex-1 rounded border bg-slate-50 px-3 text-sm text-slate-700 focus:outline-none focus:ring-4 ${
              postalCodeError
                ? 'border-red-500 focus:border-red-500 focus:ring-red-100'
                : 'border-slate-300 focus:border-[#de6c2d] focus:ring-[#f4c8b2]'
            }`}
            placeholder="주소 찾기로 입력"
            defaultValue={values?.postalCode ?? ''}
            autoComplete="postal-code"
            inputMode="numeric"
            maxLength={20}
            readOnly
            aria-invalid={Boolean(postalCodeError)}
            aria-describedby={postalCodeError ? 'auth-postal-code-error' : undefined}
            required
          />
          <button
            type="button"
            onClick={openAddressSearch}
            className="h-11 shrink-0 rounded border border-[#de6c2d] px-3 text-sm font-bold text-[#de6c2d] transition hover:bg-[#fff5ef] focus:outline-none focus:ring-4 focus:ring-[#f4c8b2]"
          >
            주소 찾기
          </button>
        </div>
        {postalCodeError ? <FieldError id="auth-postal-code-error">{postalCodeError}</FieldError> : null}
      </div>
      <label className="block text-sm font-semibold text-slate-700 sm:col-span-2">
        주소
        <input
          id="auth-address-line1"
          ref={addressLine1Ref}
          name="addressLine1"
          className={`mt-2 h-11 w-full rounded border bg-slate-50 px-3 text-sm text-slate-700 focus:outline-none focus:ring-4 ${
            addressLine1Error
              ? 'border-red-500 focus:border-red-500 focus:ring-red-100'
              : 'border-slate-300 focus:border-[#de6c2d] focus:ring-[#f4c8b2]'
          }`}
          placeholder="주소 찾기로 입력"
          defaultValue={values?.addressLine1 ?? ''}
          autoComplete="address-line1"
          maxLength={255}
          readOnly
          aria-invalid={Boolean(addressLine1Error)}
          aria-describedby={addressLine1Error ? 'auth-address-line1-error' : undefined}
          required
        />
        {addressLine1Error ? <FieldError id="auth-address-line1-error">{addressLine1Error}</FieldError> : null}
      </label>
      <label className="block text-sm font-semibold text-slate-700 sm:col-span-2">
        상세주소
        <input
          id="auth-address-line2"
          ref={addressLine2Ref}
          name="addressLine2"
          className={`mt-2 h-11 w-full rounded border px-3 text-sm focus:outline-none focus:ring-4 ${
            addressLine2Error
              ? 'border-red-500 bg-red-50 focus:border-red-500 focus:ring-red-100'
              : 'border-slate-300 focus:border-[#de6c2d] focus:ring-[#f4c8b2]'
          }`}
          placeholder="101호"
          defaultValue={values?.addressLine2 ?? ''}
          autoComplete="address-line2"
          maxLength={255}
          aria-invalid={Boolean(addressLine2Error)}
          aria-describedby={addressLine2Error ? 'auth-address-line2-error' : undefined}
          onChange={() => onClearError?.('addressLine2')}
          required
        />
        {addressLine2Error ? <FieldError id="auth-address-line2-error">{addressLine2Error}</FieldError> : null}
      </label>
      {addressSearchError ? <p className="text-sm font-semibold text-red-600 sm:col-span-2">{addressSearchError}</p> : null}
    </>
  );
}

function FieldError({ id, children }: { id: string; children: ReactNode }) {
  return (
    <p id={id} className="mt-2 text-xs font-semibold leading-5 text-red-600">
      {children}
    </p>
  );
}

type ContactAddressPayload = {
  phoneNumber: string;
  postalCode: string;
  addressLine1: string;
  addressLine2: string;
};

class FieldValidationError extends Error {
  constructor(
    public readonly field: ContactField,
    public readonly reason: string,
    message: string
  ) {
    super(message);
    this.name = 'FieldValidationError';
  }
}

function applyContactFieldErrors(
  cause: unknown,
  setContactErrors: Dispatch<SetStateAction<ContactFieldErrors>>
) {
  const fieldErrors = contactFieldErrorsFrom(cause);
  if (!fieldErrors) {
    return false;
  }
  setContactErrors(fieldErrors);
  return true;
}

function contactFieldErrorsFrom(cause: unknown): ContactFieldErrors | null {
  if (cause instanceof FieldValidationError) {
    return { [cause.field]: cause.message };
  }
  if (!(cause instanceof ApiError) || !cause.details) {
    return null;
  }
  const candidates = Array.isArray(cause.details.errors)
    ? cause.details.errors
    : [cause.details];
  const fieldErrors: ContactFieldErrors = {};
  for (const candidate of candidates) {
    if (!isObjectRecord(candidate)) {
      continue;
    }
    const field = candidate.field;
    if (!isContactField(field)) {
      continue;
    }
    const message = typeof candidate.message === 'string' && candidate.message.trim()
      ? candidate.message
      : cause.message;
    fieldErrors[field] = message;
  }
  return Object.keys(fieldErrors).length > 0 ? fieldErrors : null;
}

function clearContactFieldError(
  setContactErrors: Dispatch<SetStateAction<ContactFieldErrors>>,
  field: ContactField
) {
  setContactErrors((current) => {
    if (!current[field]) {
      return current;
    }
    const next = { ...current };
    delete next[field];
    return next;
  });
}

function isObjectRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function isContactField(value: unknown): value is ContactField {
  return value === 'phoneNumber'
    || value === 'postalCode'
    || value === 'addressLine1'
    || value === 'addressLine2';
}

function readContactAddress(form: FormData): ContactAddressPayload {
  const postalCode = String(form.get('postalCode') ?? '');
  const addressLine1 = String(form.get('addressLine1') ?? '');
  if (!postalCode.trim() || !addressLine1.trim()) {
    throw new FieldValidationError('postalCode', 'REQUIRED', '주소 찾기를 눌러 우편번호와 주소를 선택해 주세요.');
  }
  return {
    phoneNumber: normalizePhoneNumber(String(form.get('phoneNumber') ?? '')),
    postalCode: normalizePostalCode(postalCode),
    addressLine1: normalizeAddressLine1(addressLine1),
    addressLine2: normalizeAddressLine2(String(form.get('addressLine2') ?? ''))
  };
}

function normalizeProfileName(value: string) {
  const normalized = value.trim().replace(/\s+/g, ' ');
  if (!normalized) {
    throw new Error('이름을 입력해 주세요.');
  }
  if (normalized.length > 100) {
    throw new Error('이름은 100자 이하여야 합니다.');
  }
  return normalized;
}

function normalizePhoneNumber(value: string) {
  const raw = value.trim();
  if (!raw) {
    throw new FieldValidationError('phoneNumber', 'REQUIRED', '전화번호를 입력해 주세요.');
  }
  if (/[^0-9\s()-]/.test(raw)) {
    throw new FieldValidationError('phoneNumber', 'INVALID_FORMAT', '전화번호는 숫자와 하이픈만 입력해 주세요.');
  }
  const digits = raw.replace(/\D/g, '');
  if (!digits.startsWith('0') || (digits.length !== 10 && digits.length !== 11)) {
    throw new FieldValidationError('phoneNumber', 'INVALID_FORMAT', '전화번호는 지역번호를 포함해 10~11자리 숫자로 입력해 주세요.');
  }
  if (digits.length === 11) {
    return `${digits.slice(0, 3)}-${digits.slice(3, 7)}-${digits.slice(7)}`;
  }
  if (digits.startsWith('02')) {
    return `${digits.slice(0, 2)}-${digits.slice(2, 6)}-${digits.slice(6)}`;
  }
  return `${digits.slice(0, 3)}-${digits.slice(3, 6)}-${digits.slice(6)}`;
}

function normalizePostalCode(value: string) {
  const digits = value.trim().replace(/\s/g, '');
  if (!/^\d{5}$/.test(digits)) {
    throw new FieldValidationError('postalCode', 'INVALID_FORMAT', '우편번호는 5자리 숫자로 입력해 주세요.');
  }
  return digits;
}

function normalizeAddressLine1(value: string) {
  const normalized = value.trim().replace(/\s+/g, ' ');
  if (normalized.length < 5 || !/[가-힣]/.test(normalized)) {
    throw new FieldValidationError('addressLine1', 'INVALID_FORMAT', '주소는 시/군/구와 도로명 또는 지번을 포함해 입력해 주세요.');
  }
  return normalized;
}

function normalizeAddressLine2(value: string) {
  const normalized = value.trim().replace(/\s+/g, ' ');
  if (!normalized) {
    throw new FieldValidationError('addressLine2', 'REQUIRED', '상세주소를 입력해 주세요.');
  }
  return normalized;
}

function loadKakaoPostcodeScript() {
  if (window.kakao?.Postcode) {
    return Promise.resolve();
  }
  if (kakaoPostcodeScriptPromise) {
    return kakaoPostcodeScriptPromise;
  }

  kakaoPostcodeScriptPromise = new Promise<void>((resolve, reject) => {
    const existingScript = document.getElementById(KAKAO_POSTCODE_SCRIPT_ID) as HTMLScriptElement | null;
    if (existingScript) {
      existingScript.addEventListener('load', () => resolve(), { once: true });
      existingScript.addEventListener('error', () => {
        kakaoPostcodeScriptPromise = null;
        reject(new Error('Kakao postcode script failed to load.'));
      }, { once: true });
      return;
    }

    const script = document.createElement('script');
    script.id = KAKAO_POSTCODE_SCRIPT_ID;
    script.src = KAKAO_POSTCODE_SCRIPT_URL;
    script.async = true;
    script.onload = () => resolve();
    script.onerror = () => {
      kakaoPostcodeScriptPromise = null;
      script.remove();
      reject(new Error('Kakao postcode script failed to load.'));
    };
    document.head.appendChild(script);
  });

  return kakaoPostcodeScriptPromise;
}

function selectedAddressFromPostcode(data: KakaoPostcodeData) {
  const baseAddress = data.userSelectedType === 'R'
    ? data.roadAddress
    : data.jibunAddress;
  const extraAddress = data.userSelectedType === 'R' ? roadAddressExtra(data) : '';
  return `${baseAddress || data.address}${extraAddress}`.trim();
}

function roadAddressExtra(data: KakaoPostcodeData) {
  const extraParts: string[] = [];
  if (data.bname && /[동로가]$/.test(data.bname)) {
    extraParts.push(data.bname);
  }
  if (data.buildingName && data.apartment === 'Y') {
    extraParts.push(data.buildingName);
  }
  return extraParts.length > 0 ? ` (${extraParts.join(', ')})` : '';
}

function PrimaryButton({ children, disabled, className = '' }: { children: ReactNode; disabled?: boolean; className?: string }) {
  return (
    <button
      className={`h-11 w-full rounded bg-[#de6c2d] text-sm font-bold text-white transition hover:bg-[#c45c22] disabled:cursor-not-allowed disabled:bg-slate-400 disabled:hover:bg-slate-400 ${className}`}
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
      className="flex h-11 w-full items-center justify-center gap-2 rounded border border-slate-300 bg-white text-sm font-bold text-slate-800 transition hover:border-[#de6c2d] hover:text-[#de6c2d] focus:outline-none focus:ring-4 focus:ring-[#f4c8b2]"
    >
      <span className="grid h-5 w-5 place-items-center rounded-full border border-slate-200 text-xs font-black text-[#de6c2d]">G</span>
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
