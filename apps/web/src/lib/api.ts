export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '';
export const AUTH_CHANGED_EVENT = 'buildgraph-auth-change';
const ACCESS_TOKEN_KEY = 'buildgraph.token';
const REFRESH_TOKEN_KEY = 'buildgraph.refreshToken';
const AUTH_USER_KEY = 'buildgraph.authUser';

type ErrorResponseBody = {
  code?: unknown;
  message?: unknown;
  details?: unknown;
};

type ErrorDetails = Record<string, unknown>;

type ParsedErrorResponse = {
  code?: string;
  message?: string;
  details?: ErrorDetails;
};

type RefreshResponseBody = {
  accessToken?: unknown;
  refreshToken?: unknown;
};

export type AuthChangedDetail = {
  user?: unknown;
};

let refreshPromise: Promise<boolean> | null = null;

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly path: string,
    public readonly code?: string,
    message?: string,
    public readonly details?: ErrorDetails
  ) {
    super(message ?? `API ${status}: ${path}`);
    this.name = 'ApiError';
  }
}

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetchApi(path, init);

  if (!response.ok) {
    if (response.status === 401) {
      if (shouldAttemptTokenRefresh(path)) {
        if (await refreshAuthTokens()) {
          const retryResponse = await fetchApi(path, init);
          if (retryResponse.ok) {
            return parseSuccessResponse<T>(retryResponse);
          }
          if (retryResponse.status === 401) {
            clearToken();
          }
          throw await createApiError(path, retryResponse);
        }
        clearToken();
      } else if (shouldClearAuthOnUnauthorized(path)) {
        clearToken();
      }
    }

    throw await createApiError(path, response);
  }

  return parseSuccessResponse<T>(response);
}

async function fetchApi(path: string, init?: RequestInit) {
  const token = localStorage.getItem(ACCESS_TOKEN_KEY);
  const headers = new Headers(init?.headers);
  if (!(init?.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json');
  }
  if (token) headers.set('Authorization', `Bearer ${token}`);

  return fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers
  });
}

function shouldAttemptTokenRefresh(path: string) {
  return Boolean(getRefreshToken()) && !isAuthRefreshExcludedPath(path);
}

function shouldClearAuthOnUnauthorized(path: string) {
  return !isAuthRefreshExcludedPath(path);
}

function isAuthRefreshExcludedPath(path: string) {
  const pathname = path.split('?')[0];
  return [
    '/api/auth/login',
    '/api/auth/refresh',
    '/api/auth/logout',
    '/api/auth/exchange',
    '/api/users'
  ].includes(pathname) || pathname.startsWith('/api/auth/google/');
}

export async function refreshAuthTokens() {
  refreshPromise ??= requestTokenRefresh().finally(() => {
    refreshPromise = null;
  });
  return refreshPromise;
}

async function requestTokenRefresh() {
  const refreshToken = getRefreshToken();
  if (!refreshToken) {
    clearToken();
    return false;
  }

  try {
    const response = await fetch(`${API_BASE_URL}/api/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken })
    });

    if (!response.ok) {
      clearToken();
      return false;
    }

    const body = await response.json() as RefreshResponseBody;
    if (typeof body.accessToken !== 'string' || typeof body.refreshToken !== 'string') {
      clearToken();
      return false;
    }

    storeAuthTokens(body.accessToken, body.refreshToken);
    return true;
  } catch {
    clearToken();
    return false;
  }
}

async function parseSuccessResponse<T>(response: Response): Promise<T> {
  if (response.status === 204) {
    return undefined as T;
  }
  const text = await response.text();
  if (!text) {
    return undefined as T;
  }
  return JSON.parse(text) as T;
}

async function createApiError(path: string, response: Response) {
  const errorBody = await readErrorResponse(response);
  return new ApiError(response.status, path, errorBody.code, errorBody.message, errorBody.details);
}

async function readErrorResponse(response: Response): Promise<ParsedErrorResponse> {
  try {
    const body = await response.json() as ErrorResponseBody;
    return {
      code: typeof body.code === 'string' ? body.code : undefined,
      message: typeof body.message === 'string' ? body.message : undefined,
      details: toErrorDetails(body.details)
    };
  } catch {
    return {};
  }
}

function toErrorDetails(value: unknown) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return undefined;
  }
  return value as ErrorDetails;
}

function dispatchAuthChanged(detail?: AuthChangedDetail) {
  window.dispatchEvent(new CustomEvent<AuthChangedDetail>(AUTH_CHANGED_EVENT, { detail }));
}

function storeAuthTokens(accessToken: string, refreshToken: string) {
  localStorage.setItem(ACCESS_TOKEN_KEY, accessToken);
  localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken);
}

function storeAuthUser(user: unknown) {
  if (user === undefined) {
    return;
  }
  localStorage.setItem(AUTH_USER_KEY, JSON.stringify(user));
}

export function saveAuthTokens(accessToken: string, refreshToken: string, user?: unknown) {
  storeAuthTokens(accessToken, refreshToken);
  storeAuthUser(user);
  dispatchAuthChanged({ user });
}

export function saveToken(token: string) {
  localStorage.setItem(ACCESS_TOKEN_KEY, token);
  localStorage.removeItem(AUTH_USER_KEY);
  dispatchAuthChanged();
}

export function getToken() {
  return localStorage.getItem(ACCESS_TOKEN_KEY);
}

export function getRefreshToken() {
  return localStorage.getItem(REFRESH_TOKEN_KEY);
}

export function getCachedAuthUser() {
  try {
    const raw = localStorage.getItem(AUTH_USER_KEY);
    return raw ? JSON.parse(raw) as unknown : null;
  } catch {
    localStorage.removeItem(AUTH_USER_KEY);
    return null;
  }
}

export function clearToken() {
  localStorage.removeItem(ACCESS_TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
  localStorage.removeItem(AUTH_USER_KEY);
  dispatchAuthChanged();
}
