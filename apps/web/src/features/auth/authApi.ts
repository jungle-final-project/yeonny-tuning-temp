import { API_BASE_URL, ApiError, api, getRefreshToken, refreshAuthTokens } from '../../lib/api';

export type LoginResponse = {
  accessToken: string;
  refreshToken: string;
  user: {
    id: string;
    email: string;
    name: string;
    role: 'USER' | 'ADMIN';
    phoneNumber?: string | null;
    postalCode?: string | null;
    addressLine1?: string | null;
    addressLine2?: string | null;
    authProviders?: Array<'LOCAL' | 'GOOGLE'>;
  };
  profileVerificationToken?: string;
};

export type CurrentUser = LoginResponse['user'];
export type SignupResponse = LoginResponse['user'];

export function login(email: string, password: string) {
  return api<LoginResponse>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password })
  });
}

export function getCurrentUser() {
  return api<CurrentUser>('/api/auth/me');
}

export type ContactAddressPayload = {
  phoneNumber: string;
  postalCode: string;
  addressLine1: string;
  addressLine2: string;
};

type SignupPayload = ContactAddressPayload & {
  name: string;
  email: string;
  password: string;
  termsAccepted: boolean;
  marketingAccepted: boolean;
};

export function signup(payload: SignupPayload) {
  return api<SignupResponse>('/api/users', {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}

export type ProfileUpdatePayload = ContactAddressPayload & {
  currentPassword?: string;
  googleVerificationToken?: string;
  name: string;
};

export function updateCurrentUser(payload: ProfileUpdatePayload) {
  return api<CurrentUser>('/api/users/me', {
    method: 'PATCH',
    body: JSON.stringify(payload)
  });
}

export function verifyProfilePassword(password: string) {
  return api<void>('/api/users/me/password-verification', {
    method: 'POST',
    body: JSON.stringify({ password })
  });
}

type AuthExchangePayload = {
  code: string;
  termsAccepted?: boolean;
  marketingAccepted?: boolean;
  phoneNumber?: string;
  postalCode?: string;
  addressLine1?: string;
  addressLine2?: string;
};

export function exchangeAuthCode(payload: AuthExchangePayload) {
  return api<LoginResponse>('/api/auth/exchange', {
    method: 'POST',
    body: JSON.stringify(payload)
  });
}

export function googleOAuthStartUrl(redirect: string) {
  return `${API_BASE_URL}/api/auth/google/start?redirect=${encodeURIComponent(redirect)}`;
}

export async function logout(refreshToken: string) {
  try {
    await requestLogout(refreshToken);
  } catch (error) {
    if (error instanceof ApiError && error.status === 401 && await refreshAuthTokens()) {
      const rotatedRefreshToken = getRefreshToken();
      if (rotatedRefreshToken) {
        await requestLogout(rotatedRefreshToken);
        return;
      }
    }
    throw error;
  }
}

function requestLogout(refreshToken: string) {
  return api<void>('/api/auth/logout', {
    method: 'POST',
    body: JSON.stringify({ refreshToken })
  });
}
