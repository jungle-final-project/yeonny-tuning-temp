import { api } from '../../lib/api';

export type LoginResponse = {
  token: string;
  user: {
    id: string;
    email: string;
    role: 'USER' | 'ADMIN';
  };
};

export type CurrentUser = LoginResponse['user'];

export function login(email: string, password: string) {
  return api<LoginResponse>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password })
  });
}

export function getCurrentUser() {
  return api<CurrentUser>('/api/auth/me');
}

export function signup(name: string, email: string, password: string) {
  return api('/api/users', {
    method: 'POST',
    body: JSON.stringify({ name, email, password })
  });
}
