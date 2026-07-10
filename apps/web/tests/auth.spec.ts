import { expect, test } from '@playwright/test';

test('shows login API error message and does not save tokens', async ({ page }) => {
  await page.route('**/api/auth/login', async (route) => {
    expect(JSON.parse(route.request().postData() ?? '{}')).toEqual({
      email: 'user@example.com',
      password: 'wrong-password'
    });
    await route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 'UNAUTHORIZED',
        message: '이메일 또는 비밀번호가 올바르지 않습니다.'
      })
    });
  });

  await page.goto('/login');
  await page.getByLabel('이메일').fill('user@example.com');
  await page.getByLabel('비밀번호').fill('wrong-password');
  await page.getByRole('button', { name: '로그인' }).click();

  await expect(page.getByText('이메일 또는 비밀번호가 올바르지 않습니다.')).toBeVisible();
  expect(await page.evaluate(() => localStorage.getItem('buildgraph.token'))).toBeNull();
  expect(await page.evaluate(() => localStorage.getItem('buildgraph.refreshToken'))).toBeNull();
});

test('updates header from login response before auth me finishes', async ({ page }) => {
  await page.route('**/api/auth/login', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        accessToken: 'jwt-fast-user',
        refreshToken: 'refresh-fast-user',
        user: {
          id: '00000000-0000-4000-8000-000000001088',
          email: 'fast@example.com',
          name: 'Fast User',
          role: 'USER'
        }
      })
    });
  });
  await page.route('**/api/auth/me', async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 5_000));
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: '00000000-0000-4000-8000-000000001088',
        email: 'fast@example.com',
        name: 'Fast User',
        role: 'USER'
      })
    });
  });

  await page.goto('/login');
  await page.getByLabel('이메일').fill('fast@example.com');
  await page.getByLabel('비밀번호').fill('passw0rd!');
  await page.getByRole('button', { name: '로그인' }).click();

  await expect(page).toHaveURL('/');
  await expect(page.getByText('로그인됨 · fast@example.com · 사용자')).toBeVisible({ timeout: 2_000 });
  await expect(page.getByText('Fast User')).toBeVisible({ timeout: 2_000 });
  await expect(page.getByRole('navigation').getByRole('link', { name: '관리자' })).toHaveCount(0);
  expect(await page.evaluate(() => JSON.parse(localStorage.getItem('buildgraph.authUser') ?? '{}'))).toMatchObject({
    email: 'fast@example.com',
    name: 'Fast User',
    role: 'USER'
  });
});

test('shows admin navigation only for ADMIN role', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-admin-token');
    localStorage.setItem('buildgraph.authUser', JSON.stringify({
      id: 'admin-001',
      email: 'admin@example.com',
      name: 'Admin User',
      role: 'ADMIN'
    }));
  });
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'admin-001',
        email: 'admin@example.com',
        name: 'Admin User',
        role: 'ADMIN'
      })
    });
  });

  await page.goto('/login');

  await expect(page.getByText('로그인됨 · admin@example.com · 관리자')).toBeVisible();
  await expect(page.getByRole('navigation').getByRole('link', { name: '관리자' })).toHaveAttribute('href', '/admin');
});

test('blocks USER accounts on admin login and does not save tokens', async ({ page }) => {
  await page.route('**/api/auth/login', async (route) => {
    expect(JSON.parse(route.request().postData() ?? '{}')).toEqual({
      email: 'user@example.com',
      password: 'passw0rd!'
    });
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        accessToken: 'jwt-user-token',
        refreshToken: 'refresh-user-token',
        user: {
          id: 'user-001',
          email: 'user@example.com',
          name: 'Regular User',
          role: 'USER'
        }
      })
    });
  });

  await page.goto('/admin/login?redirect=/admin/parts');
  await page.getByLabel('이메일').fill('user@example.com');
  await page.getByLabel('비밀번호').fill('passw0rd!');
  await page.getByRole('button', { name: '관리자 로그인' }).click();

  await expect(page.getByText('관리자 권한이 있는 계정만 관리자 화면에 로그인할 수 있습니다.')).toBeVisible();
  expect(await page.evaluate(() => localStorage.getItem('buildgraph.token'))).toBeNull();
  expect(await page.evaluate(() => localStorage.getItem('buildgraph.refreshToken'))).toBeNull();
});

test('exchanges Google callback code and stores returned tokens', async ({ page }) => {
  let exchangeCalls = 0;
  await page.route('**/api/auth/exchange', async (route) => {
    exchangeCalls += 1;
    expect(JSON.parse(route.request().postData() ?? '{}')).toEqual({ code: 'one-time-code' });
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        accessToken: 'jwt-google-user',
        refreshToken: 'refresh-google-user',
        user: {
          id: 'google-user-001',
          email: 'google-user@example.com',
          name: 'Google User',
          role: 'USER'
        }
      })
    });
  });
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'google-user-001',
        email: 'google-user@example.com',
        name: 'Google User',
        role: 'USER'
      })
    });
  });

  await page.goto('/auth/callback?code=one-time-code&redirect=/login');

  await expect(page).toHaveURL('/login');
  await expect.poll(() => exchangeCalls).toBe(1);
  expect(await page.evaluate(() => localStorage.getItem('buildgraph.token'))).toBe('jwt-google-user');
  expect(await page.evaluate(() => localStorage.getItem('buildgraph.refreshToken'))).toBe('refresh-google-user');
});

test('shows terms step for new Google users and retries exchange with consent', async ({ page }) => {
  let exchangeCalls = 0;
  await page.route('**/api/auth/exchange', async (route) => {
    exchangeCalls += 1;
    const payload = JSON.parse(route.request().postData() ?? '{}');
    if (exchangeCalls === 1) {
      expect(payload).toEqual({ code: 'terms-code' });
      await route.fulfill({
        status: 400,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'VALIDATION_ERROR',
          message: '약관 동의가 필요합니다.',
          details: {
            reason: 'TERMS_REQUIRED',
            email: 'new-google@example.com',
            name: 'New Google'
          }
        })
      });
      return;
    }
    expect(payload).toEqual({
      code: 'terms-code',
      termsAccepted: true,
      marketingAccepted: false
    });
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        accessToken: 'jwt-new-google',
        refreshToken: 'refresh-new-google',
        user: {
          id: 'new-google-001',
          email: 'new-google@example.com',
          name: 'New Google',
          role: 'USER'
        }
      })
    });
  });

  await page.goto('/auth/callback?code=terms-code&redirect=/login');

  await expect(page.getByRole('heading', { name: 'Google 회원가입 완료' })).toBeVisible();
  await expect(page.getByText('new-google@example.com 계정으로 가입을 완료합니다.')).toBeVisible();
  await page.getByLabel('서비스 이용약관 및 로그 업로드 정책 확인').check();
  await page.getByRole('button', { name: '가입 완료' }).click();

  await expect(page).toHaveURL('/login');
  await expect.poll(() => exchangeCalls).toBe(2);
  expect(await page.evaluate(() => localStorage.getItem('buildgraph.token'))).toBe('jwt-new-google');
});

test('shows callback failure when Google exchange code is expired', async ({ page }) => {
  await page.route('**/api/auth/exchange', async (route) => {
    await route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 'UNAUTHORIZED',
        message: 'Google login session has expired.'
      })
    });
  });

  await page.goto('/auth/callback?code=expired-code');

  await expect(page.getByRole('heading', { name: 'Google 로그인 실패' })).toBeVisible();
  await expect(page.getByText('Google login session has expired.')).toBeVisible();
});

test('refreshes expired access token and retries current user request', async ({ page }) => {
  let expiredMeCalls = 0;
  let refreshedMeCalls = 0;
  let refreshCalls = 0;
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'expired-access-token');
    localStorage.setItem('buildgraph.refreshToken', 'valid-refresh-token');
  });
  await page.route('**/api/auth/me', async (route) => {
    const authorization = route.request().headers().authorization;
    if (authorization === 'Bearer expired-access-token') {
      expiredMeCalls += 1;
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 'UNAUTHORIZED',
          message: 'Access token is expired.'
        })
      });
      return;
    }

    expect(authorization).toBe('Bearer refreshed-access-token');
    refreshedMeCalls += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: '00000000-0000-4000-8000-000000001077',
        email: 'refreshed@example.com',
        name: 'Refresh User',
        role: 'USER'
      })
    });
  });
  await page.route('**/api/auth/refresh', async (route) => {
    refreshCalls += 1;
    expect(JSON.parse(route.request().postData() ?? '{}')).toEqual({
      refreshToken: 'valid-refresh-token'
    });
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        accessToken: 'refreshed-access-token',
        refreshToken: 'rotated-refresh-token'
      })
    });
  });

  await page.goto('/login');

  await expect(page.getByText('refreshed@example.com')).toBeVisible();
  await expect(page.getByText('Refresh User')).toBeVisible();
  expect(expiredMeCalls).toBeGreaterThanOrEqual(1);
  expect(refreshedMeCalls).toBeGreaterThanOrEqual(1);
  expect(refreshCalls).toBe(1);
  expect(await page.evaluate(() => localStorage.getItem('buildgraph.token'))).toBe('refreshed-access-token');
  expect(await page.evaluate(() => localStorage.getItem('buildgraph.refreshToken'))).toBe('rotated-refresh-token');
});

test('clears stored auth when protected API returns 401 without refresh token', async ({ page }) => {
  let protectedCalls = 0;
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'expired-access-token');
    localStorage.setItem('buildgraph.authUser', JSON.stringify({
      id: 'user-no-refresh',
      email: 'no-refresh@example.com',
      name: 'No Refresh User',
      role: 'USER'
    }));
  });
  await page.route('**/api/protected/no-refresh', async (route) => {
    protectedCalls += 1;
    await route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 'UNAUTHORIZED',
        message: '로그인이 필요합니다.'
      })
    });
  });

  await page.goto('/login');
  const result = await page.evaluate(async () => {
    const apiModulePath = '/src/lib/api.ts';
    const { api, ApiError } = await import(/* @vite-ignore */ apiModulePath);
    try {
      await api('/api/protected/no-refresh');
      return { ok: true };
    } catch (error) {
      const isApiError = error instanceof ApiError;
      const apiError = error as { status?: number; code?: string };
      return {
        ok: false,
        isApiError,
        status: isApiError ? apiError.status : null,
        code: isApiError ? apiError.code : null,
        message: error instanceof Error ? error.message : ''
      };
    }
  });

  expect(result).toEqual({
    ok: false,
    isApiError: true,
    status: 401,
    code: 'UNAUTHORIZED',
    message: '로그인이 필요합니다.'
  });
  expect(protectedCalls).toBe(1);
  expect(await page.evaluate(() => localStorage.getItem('buildgraph.token'))).toBeNull();
  expect(await page.evaluate(() => localStorage.getItem('buildgraph.refreshToken'))).toBeNull();
  expect(await page.evaluate(() => localStorage.getItem('buildgraph.authUser'))).toBeNull();
});

test('clears stored auth when refresh request fails and does not retry original API', async ({ page }) => {
  let protectedCalls = 0;
  let refreshCalls = 0;
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'expired-access-token');
    localStorage.setItem('buildgraph.refreshToken', 'stale-refresh-token');
    localStorage.setItem('buildgraph.authUser', JSON.stringify({
      id: 'user-stale-refresh',
      email: 'stale-refresh@example.com',
      name: 'Stale Refresh User',
      role: 'USER'
    }));
  });
  await page.route('**/api/protected/refresh-fails', async (route) => {
    protectedCalls += 1;
    await route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 'UNAUTHORIZED',
        message: 'Access token is expired.'
      })
    });
  });
  await page.route('**/api/auth/refresh', async (route) => {
    refreshCalls += 1;
    await route.fulfill({
      status: 500,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 'INTERNAL_ERROR',
        message: 'refresh failed'
      })
    });
  });

  await page.goto('/login');
  const result = await page.evaluate(async () => {
    const apiModulePath = '/src/lib/api.ts';
    const { api, ApiError } = await import(/* @vite-ignore */ apiModulePath);
    try {
      await api('/api/protected/refresh-fails');
      return { ok: true };
    } catch (error) {
      const isApiError = error instanceof ApiError;
      const apiError = error as { status?: number; code?: string };
      return {
        ok: false,
        isApiError,
        status: isApiError ? apiError.status : null,
        code: isApiError ? apiError.code : null
      };
    }
  });

  expect(result).toEqual({
    ok: false,
    isApiError: true,
    status: 401,
    code: 'UNAUTHORIZED'
  });
  expect(protectedCalls).toBe(1);
  expect(refreshCalls).toBe(1);
  expect(await page.evaluate(() => localStorage.getItem('buildgraph.token'))).toBeNull();
  expect(await page.evaluate(() => localStorage.getItem('buildgraph.refreshToken'))).toBeNull();
  expect(await page.evaluate(() => localStorage.getItem('buildgraph.authUser'))).toBeNull();
});

test('clears stored auth when refresh response body is invalid', async ({ page }) => {
  let protectedCalls = 0;
  let refreshCalls = 0;
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'expired-access-token');
    localStorage.setItem('buildgraph.refreshToken', 'invalid-body-refresh-token');
    localStorage.setItem('buildgraph.authUser', JSON.stringify({
      id: 'user-invalid-refresh',
      email: 'invalid-refresh@example.com',
      name: 'Invalid Refresh User',
      role: 'USER'
    }));
  });
  await page.route('**/api/protected/invalid-refresh-body', async (route) => {
    protectedCalls += 1;
    await route.fulfill({
      status: 401,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 'UNAUTHORIZED',
        message: 'Access token is expired.'
      })
    });
  });
  await page.route('**/api/auth/refresh', async (route) => {
    refreshCalls += 1;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        accessToken: 'new-access-token'
      })
    });
  });

  await page.goto('/login');
  await page.evaluate(async () => {
    const apiModulePath = '/src/lib/api.ts';
    const { api } = await import(/* @vite-ignore */ apiModulePath);
    await api('/api/protected/invalid-refresh-body').catch(() => undefined);
  });

  expect(protectedCalls).toBe(1);
  expect(refreshCalls).toBe(1);
  expect(await page.evaluate(() => localStorage.getItem('buildgraph.token'))).toBeNull();
  expect(await page.evaluate(() => localStorage.getItem('buildgraph.refreshToken'))).toBeNull();
  expect(await page.evaluate(() => localStorage.getItem('buildgraph.authUser'))).toBeNull();
});

test('preserves ErrorResponse details on ApiError', async ({ page }) => {
  await page.route('**/api/protected/details-error', async (route) => {
    await route.fulfill({
      status: 400,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 'VALIDATION_ERROR',
        message: '요청 값이 올바르지 않습니다.',
        details: {
          field: 'email',
          reason: 'INVALID_FORMAT'
        }
      })
    });
  });

  await page.goto('/login');
  const result = await page.evaluate(async () => {
    const apiModulePath = '/src/lib/api.ts';
    const { api, ApiError } = await import(/* @vite-ignore */ apiModulePath);
    try {
      await api('/api/protected/details-error');
      return { ok: true };
    } catch (error) {
      const isApiError = error instanceof ApiError;
      const apiError = error as { status?: number; path?: string; code?: string; details?: unknown };
      return {
        ok: false,
        isApiError,
        status: isApiError ? apiError.status : null,
        path: isApiError ? apiError.path : null,
        code: isApiError ? apiError.code : null,
        message: error instanceof Error ? error.message : '',
        details: isApiError ? apiError.details : null
      };
    }
  });

  expect(result).toEqual({
    ok: false,
    isApiError: true,
    status: 400,
    path: '/api/protected/details-error',
    code: 'VALIDATION_ERROR',
    message: '요청 값이 올바르지 않습니다.',
    details: {
      field: 'email',
      reason: 'INVALID_FORMAT'
    }
  });
});

test('calls logout API and clears stored auth tokens', async ({ page }) => {
  let logoutCalled = false;
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'logout-access-token');
    localStorage.setItem('buildgraph.refreshToken', 'logout-refresh-token');
    localStorage.setItem('buildgraph.authUser', JSON.stringify({
      id: '00000000-0000-4000-8000-000000001066',
      email: 'logout@example.com',
      name: 'Logout User',
      role: 'USER'
    }));
  });
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: '00000000-0000-4000-8000-000000001066',
        email: 'logout@example.com',
        name: 'Logout User',
        role: 'USER'
      })
    });
  });
  await page.route('**/api/auth/logout', async (route) => {
    logoutCalled = true;
    expect(route.request().headers().authorization).toBe('Bearer logout-access-token');
    expect(JSON.parse(route.request().postData() ?? '{}')).toEqual({
      refreshToken: 'logout-refresh-token'
    });
    await route.fulfill({ status: 204 });
  });

  await page.goto('/login');
  await expect(page.getByText('Logout User')).toBeVisible();
  await page.getByRole('button', { name: '로그아웃' }).click();

  await expect.poll(() => logoutCalled).toBe(true);
  await page.waitForFunction(() =>
    localStorage.getItem('buildgraph.token') === null &&
    localStorage.getItem('buildgraph.refreshToken') === null &&
    localStorage.getItem('buildgraph.authUser') === null
  );
  expect(await page.evaluate(() => localStorage.getItem('buildgraph.token'))).toBeNull();
  expect(await page.evaluate(() => localStorage.getItem('buildgraph.refreshToken'))).toBeNull();
  expect(await page.evaluate(() => localStorage.getItem('buildgraph.authUser'))).toBeNull();
  await expect(page).toHaveURL('/login');
});

test('clears stored auth tokens even when logout API fails', async ({ page }) => {
  let logoutCalled = false;
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'logout-fail-access-token');
    localStorage.setItem('buildgraph.refreshToken', 'logout-fail-refresh-token');
    localStorage.setItem('buildgraph.authUser', JSON.stringify({
      id: '00000000-0000-4000-8000-000000001067',
      email: 'logout-fail@example.com',
      name: 'Logout Fail User',
      role: 'USER'
    }));
  });
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: '00000000-0000-4000-8000-000000001067',
        email: 'logout-fail@example.com',
        name: 'Logout Fail User',
        role: 'USER'
      })
    });
  });
  await page.route('**/api/auth/logout', async (route) => {
    logoutCalled = true;
    expect(route.request().headers().authorization).toBe('Bearer logout-fail-access-token');
    await route.fulfill({
      status: 500,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 'INTERNAL_ERROR',
        message: 'logout failed'
      })
    });
  });

  await page.goto('/login');
  await expect(page.getByText('Logout Fail User')).toBeVisible();
  await page.getByRole('button', { name: '로그아웃' }).click();

  await expect.poll(() => logoutCalled).toBe(true);
  await page.waitForFunction(() =>
    localStorage.getItem('buildgraph.token') === null &&
    localStorage.getItem('buildgraph.refreshToken') === null &&
    localStorage.getItem('buildgraph.authUser') === null
  );
  expect(await page.evaluate(() => localStorage.getItem('buildgraph.token'))).toBeNull();
  expect(await page.evaluate(() => localStorage.getItem('buildgraph.refreshToken'))).toBeNull();
  expect(await page.evaluate(() => localStorage.getItem('buildgraph.authUser'))).toBeNull();
  await expect(page).toHaveURL('/login');
});

test('submits signup form with the OpenAPI user payload', async ({ page }) => {
  await page.route('**/api/users', async (route) => {
    expect(JSON.parse(route.request().postData() ?? '{}')).toEqual({
      name: '홍길동',
      email: 'new-user@example.com',
      password: 'passw0rd!',
      termsAccepted: true,
      marketingAccepted: false
    });
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify({
        id: '00000000-0000-4000-8000-000000001099',
        email: 'new-user@example.com',
        name: '홍길동',
        role: 'USER'
      })
    });
  });

  await page.goto('/signup');
  await page.getByLabel('이름').fill('홍길동');
  await page.getByLabel('이메일').fill('new-user@example.com');
  await page.getByLabel('비밀번호', { exact: true }).fill('passw0rd!');
  await page.getByLabel('비밀번호 확인').fill('passw0rd!');
  await page.getByLabel('서비스 이용약관 및 로그 업로드 정책 확인').check();
  await page.getByRole('button', { name: '회원가입', exact: true }).click();

  await expect(page).toHaveURL('/login');
});

test('shows signup API error message', async ({ page }) => {
  await page.route('**/api/users', async (route) => {
    await route.fulfill({
      status: 409,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 'DUPLICATE_RESOURCE',
        message: '이미 가입된 이메일입니다.'
      })
    });
  });

  await page.goto('/signup');
  await page.getByLabel('이름').fill('홍길동');
  await page.getByLabel('이메일').fill('user@example.com');
  await page.getByLabel('비밀번호', { exact: true }).fill('passw0rd!');
  await page.getByLabel('비밀번호 확인').fill('passw0rd!');
  await page.getByLabel('서비스 이용약관 및 로그 업로드 정책 확인').check();
  await page.getByRole('button', { name: '회원가입', exact: true }).click();

  await expect(page.getByText('이미 가입된 이메일입니다.')).toBeVisible();
  await expect(page).toHaveURL('/signup');
});
