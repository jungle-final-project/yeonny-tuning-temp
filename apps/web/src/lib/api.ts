export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '';
export const AUTH_CHANGED_EVENT = 'buildgraph-auth-change';

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly path: string
  ) {
    super(`API ${status}: ${path}`);
  }
}

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const token = localStorage.getItem('buildgraph.token');
  const headers = new Headers(init?.headers);
  if (!(init?.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json');
  }
  if (token) headers.set('Authorization', `Bearer ${token}`);

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers
  });

  if (!response.ok) {
    throw new ApiError(response.status, path);
  }

  return response.json() as Promise<T>;
}

export function saveToken(token: string) {
  localStorage.setItem('buildgraph.token', token);
  window.dispatchEvent(new Event(AUTH_CHANGED_EVENT));
}

export function getToken() {
  return localStorage.getItem('buildgraph.token');
}

export function clearToken() {
  localStorage.removeItem('buildgraph.token');
  window.dispatchEvent(new Event(AUTH_CHANGED_EVENT));
}
