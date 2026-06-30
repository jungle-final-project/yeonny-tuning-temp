import { expect, test } from '@playwright/test';

async function openHomeAsUser(page: import('@playwright/test').Page) {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
  });
  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'user-1004',
        email: 'user@example.com',
        name: '테스트 사용자',
        role: 'USER'
      })
    });
  });
  await page.goto('/');
}

test('renders the home command center with primary quote actions', async ({ page }) => {
  await openHomeAsUser(page);
  const main = page.getByRole('main');

  await expect(main.getByRole('heading', { name: '어떤 PC 견적이 필요하세요?' })).toBeVisible();
  await expect(main.getByRole('textbox', { name: '원하는 PC 사양 입력' })).toBeVisible();
  await expect(main.getByRole('button', { name: 'QHD 게임' })).toBeVisible();
  await expect(main.getByRole('button', { name: 'AI CUDA 실습' })).toBeVisible();
  await expect(main.getByRole('button', { name: '견적 상담 시작' })).toBeVisible();
});

test('renders bright shopping sections for product discovery', async ({ page }) => {
  await openHomeAsUser(page);
  const main = page.getByRole('main');

  await expect(main.getByRole('heading', { name: '오늘의 추천 견적' })).toBeVisible();
  await expect(main.getByRole('heading', { name: '인기 부품 랭킹' })).toBeVisible();
  await expect(main.getByText('SALE', { exact: true }).first()).toBeVisible();
  await expect(main.getByRole('link', { name: '인기 부품 1번 보기' })).toHaveAttribute('href', '/self-quote?category=GPU');
  await expect(main.getByRole('link', { name: '셀프 견적 전체 보기' })).toHaveAttribute('href', '/self-quote');
});

test('keeps shared header and navigation destinations unchanged', async ({ page }) => {
  await openHomeAsUser(page);
  const header = page.locator('header');
  const nav = page.getByRole('navigation');

  await expect(header.getByRole('link', { name: 'AI 견적' })).toHaveAttribute('href', '/requirements/new');
  await expect(header.getByRole('link', { name: '내 견적함' })).toHaveAttribute('href', '/my/quotes');
  await expect(header.getByRole('link', { name: 'AS 접수' })).toHaveAttribute('href', '/support/new');
  await expect(nav.getByRole('link', { name: '홈' })).toHaveAttribute('href', '/');
  await expect(nav.getByRole('link', { name: '셀프 견적' })).toHaveAttribute('href', '/self-quote');
  await expect(nav.getByRole('link', { name: '관리자' })).toHaveAttribute('href', '/admin');
});

test('keeps the home command center usable on mobile width', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openHomeAsUser(page);
  const main = page.getByRole('main');

  await expect(main.getByRole('heading', { name: '어떤 PC 견적이 필요하세요?' })).toBeVisible();
  await expect(main.getByRole('button', { name: '견적 상담 시작' })).toBeVisible();

  await main.getByRole('textbox', { name: '원하는 PC 사양 입력' }).fill('300만원 안에 예산으로 컴퓨터를 맞추고 싶어');
  await main.getByRole('button', { name: '견적 상담 시작' }).click();
  await expect(page.getByTestId('assistant-bar')).toBeVisible();
  await expect(page.getByTestId('wizard-options').getByRole('button', { name: '게임' })).toBeVisible();

  const hasBodyOverflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
  expect(hasBodyOverflow).toBe(false);
});

test('starts a local consultation and renders simulated recommendations', async ({ page }) => {
  await openHomeAsUser(page);
  const main = page.getByRole('main');

  await main.getByRole('textbox', { name: '원하는 PC 사양 입력' }).fill('300만원 안에 예산으로 컴퓨터를 맞추고 싶어');
  await main.getByRole('button', { name: '견적 상담 시작' }).click();

  await expect(main.getByRole('heading', { name: '추천 컴퓨터를 메인화면에 제공해드렸습니다' })).toBeVisible();
  await expect(main.getByText('균형형 표준 견적')).toBeVisible();
  await expect(main.getByRole('heading', { name: '부품 바로가기' })).toBeVisible();
  await expect(page.getByTestId('assistant-bar')).toBeVisible();
  await expect(page.getByTestId('assistant-answer')).toContainText('추천 컴퓨터를 메인화면에 제공해드렸습니다');
  await expect(page.getByTestId('wizard-options').getByRole('button', { name: '게임' })).toBeVisible();
  await expect(page.getByTestId('wizard-options').getByRole('button', { name: 'AI/CUDA' })).toBeVisible();
  await expect(page.getByRole('textbox', { name: 'AI에게 추가 질문' })).toBeVisible();
});

test('updates recommendation cards from wizard choices', async ({ page }) => {
  await openHomeAsUser(page);
  const main = page.getByRole('main');

  await main.getByRole('textbox', { name: '원하는 PC 사양 입력' }).fill('300만원 안에 예산으로 컴퓨터를 맞추고 싶어');
  await main.getByRole('button', { name: '견적 상담 시작' }).click();

  await page.getByTestId('wizard-options').getByRole('button', { name: '게임' }).click();
  await expect(page.getByTestId('assistant-answer')).toContainText('게임용 기준으로 추천을 조정했습니다');
  await expect(page.getByTestId('wizard-options').getByRole('button', { name: 'FHD' })).toBeVisible();
  await expect(page.getByTestId('wizard-options').getByRole('button', { name: 'QHD' })).toBeVisible();
  await expect(main.getByText('QHD 게임 균형형')).toBeVisible();

  await page.getByTestId('wizard-options').getByRole('button', { name: 'QHD' }).click();
  await expect(page.getByTestId('assistant-answer')).toContainText('QHD 기준으로 추천 컴퓨터를 다시 정리했습니다');
  await expect(main.getByText('QHD 고주사율 확장형')).toBeVisible();
});

test('keeps follow-up text input as a secondary assistant path', async ({ page }) => {
  await openHomeAsUser(page);
  const main = page.getByRole('main');

  await main.getByRole('textbox', { name: '원하는 PC 사양 입력' }).fill('200만원 QHD 게임용 PC');
  await main.getByRole('button', { name: '견적 상담 시작' }).click();
  await page.getByRole('textbox', { name: 'AI에게 추가 질문' }).fill('저소음으로 바꿔줘');
  await page.getByRole('button', { name: '질문 보내기' }).click();

  await expect(main.getByRole('heading', { name: '저소음 추천을 조정했습니다' })).toBeVisible();
  await expect(main.getByText('저소음 균형형')).toBeVisible();
  await expect(page.getByTestId('assistant-answer')).toContainText('저소음 기준으로 추천 컴퓨터를 다시 정리했습니다');
  await expect(page.getByTestId('wizard-options').getByRole('button', { name: '게임' })).toBeVisible();
});

test('lets desktop users drag the assistant bar', async ({ page }) => {
  await openHomeAsUser(page);
  const main = page.getByRole('main');

  await main.getByRole('textbox', { name: '원하는 PC 사양 입력' }).fill('AI CUDA 실습용 300만원 PC');
  await main.getByRole('button', { name: '견적 상담 시작' }).click();

  const assistantBar = page.getByTestId('assistant-bar');
  const before = await assistantBar.boundingBox();
  expect(before).not.toBeNull();

  await page.mouse.move(before!.x + 20, before!.y + 20);
  await page.mouse.down();
  await page.mouse.move(before!.x - 160, before!.y - 120, { steps: 8 });
  await page.mouse.up();

  const after = await assistantBar.boundingBox();
  expect(after).not.toBeNull();
  expect(Math.abs(after!.x - before!.x)).toBeGreaterThan(40);
  expect(Math.abs(after!.y - before!.y)).toBeGreaterThan(40);
});
