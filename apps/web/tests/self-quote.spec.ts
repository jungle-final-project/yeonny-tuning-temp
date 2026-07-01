import { expect, test } from '@playwright/test';

test('filters internal assets by sidebar category on self quote page', async ({ page }) => {
  const requestedCategories: string[] = [];
  const emptyDraft = {
    id: 'draft-test',
    status: 'ACTIVE',
    name: '셀프 견적',
    items: [],
    totalPrice: 0,
    itemCount: 0
  };
  const gpuDraft = {
    id: 'draft-test',
    status: 'ACTIVE',
    name: '셀프 견적',
    items: [
      {
        id: 'draft-item-gpu-test',
        partId: 'part-gpu-test',
        category: 'GPU',
        name: 'RTX 4070 SUPER 테스트',
        manufacturer: 'NVIDIA',
        quantity: 1,
        unitPriceAtAdd: 890000,
        currentPrice: 890000,
        lineTotal: 890000,
        attributes: {},
        externalOffer: {
          imageUrl: 'https://example.test/rtx4070.png',
          supplierName: '테스트몰',
          offerUrl: 'https://example.test/rtx4070',
          lowPrice: 890000,
          source: 'NAVER_SHOPPING_SEARCH'
        }
      }
    ],
    totalPrice: 890000,
    itemCount: 1
  };
  let draft: unknown = emptyDraft;

  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
  });

  await page.route('**/api/quote-drafts/current**', async (route) => {
    const method = route.request().method();
    if (method === 'PUT') {
      draft = gpuDraft;
    }
    if (method === 'DELETE') {
      draft = emptyDraft;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(draft)
    });
  });

  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.includes('/price-history')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          partId: 'part-gpu-test',
          partName: 'RTX 4070 SUPER 테스트',
          currentPrice: 890000,
          days: 3650,
          source: 'NAVER_SHOPPING_SEARCH',
          items: [
            { price: 890000, source: 'NAVER_SHOPPING_SEARCH', collectedAt: '2026-06-29T00:00:00Z' }
          ],
          summary: {
            sampleCount: 1,
            currentPrice: 890000,
            minPrice: 890000,
            maxPrice: 890000,
            firstPrice: 890000,
            lastPrice: 890000,
            changeAmount: 0,
            changeRatePercent: 0
          }
        })
      });
      return;
    }
    const category = url.searchParams.get('category') ?? '';
    requestedCategories.push(category);

    const items = category === 'GPU'
      ? [
          {
            id: 'part-gpu-test',
            category: 'GPU',
            name: 'RTX 4070 SUPER 테스트',
            manufacturer: 'NVIDIA',
            price: 890000,
            status: 'ACTIVE',
            benchmarkSummary: { score: 92.4 },
            externalOffer: {
              imageUrl: 'https://example.test/rtx4070.png',
              supplierName: '테스트몰',
              offerUrl: 'https://example.test/rtx4070',
              lowPrice: 890000,
              source: 'NAVER_SHOPPING_SEARCH'
            }
          }
        ]
      : [
          {
            id: 'part-cpu-test',
            category: 'CPU',
            name: 'Ryzen 5 테스트',
            manufacturer: 'AMD',
            price: 260000,
            status: 'ACTIVE',
            benchmarkSummary: { score: 81.1 },
            externalOffer: {
              imageUrl: 'https://example.test/ryzen.png',
              supplierName: 'CPU테스트몰',
              offerUrl: 'https://example.test/ryzen',
              lowPrice: 260000,
              source: 'NAVER_SHOPPING_SEARCH'
            }
          }
        ];

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items,
        page: 0,
        size: 50,
        total: items.length
      })
    });
  });

  await page.goto('/self-quote');
  await expect(page.getByText('셀프 견적 / 전체 부품 목록')).toBeVisible();
  await expect(page.getByText('Ryzen 5 테스트')).toBeVisible();
  await expect(page.getByRole('img', { name: 'Ryzen 5 테스트 제품 사진' })).toBeVisible();
  await expect(page.getByText('CPU테스트몰')).toBeVisible();

  await page.getByRole('button', { name: 'GPU' }).click();

  await expect(page.getByText('GPU 부품 목록')).toBeVisible();
  await expect(page.getByText('RTX 4070 SUPER 테스트')).toBeVisible();
  await expect(page.getByRole('img', { name: 'RTX 4070 SUPER 테스트 제품 사진' })).toBeVisible();
  await expect(page.getByText('테스트몰')).toBeVisible();
  await expect(page.getByText('ACTIVE')).toHaveCount(0);
  await expect(page.getByText('92.4')).toHaveCount(0);
  expect(requestedCategories).toContain('GPU');

  await page.getByRole('button', { name: 'RTX 4070 SUPER 테스트 견적 담기' }).click();
  await expect(page.getByText('견적 합계')).toBeVisible();
  await expect(page.getByText('890,000원')).toHaveCount(3);
  await expect(page.getByText('가격 기록 1개')).toBeVisible();

  await page.getByRole('button', { name: 'RTX 4070 SUPER 테스트 견적에서 제거' }).click();
  await expect(page.getByText('왼쪽 목록에서 부품을 담으면 이곳에 내 견적이 쌓입니다.')).toBeVisible();
});

test('self quote chatbot sends current draft and applies a remove action after confirmation', async ({ page }) => {
  const buildChatBodies: unknown[] = [];
  let deleteRequests = 0;
  const gpuDraft = {
    id: 'draft-chat-test',
    status: 'ACTIVE',
    name: '셀프 견적',
    items: [
      {
        id: 'draft-item-gpu-chat',
        partId: 'part-gpu-chat',
        category: 'GPU',
        name: 'RTX 5070 챗봇 테스트',
        manufacturer: 'NVIDIA',
        quantity: 1,
        unitPriceAtAdd: 890000,
        currentPrice: 890000,
        lineTotal: 890000,
        attributes: {}
      }
    ],
    totalPrice: 890000,
    itemCount: 1
  };
  const emptyDraft = {
    id: 'draft-chat-test',
    status: 'ACTIVE',
    name: '셀프 견적',
    items: [],
    totalPrice: 0,
    itemCount: 0
  };
  let draft = gpuDraft;

  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
    localStorage.setItem('buildgraph.authUser', JSON.stringify({
      id: 'user-test',
      email: 'user@example.com',
      name: 'Demo User',
      role: 'USER'
    }));
    sessionStorage.clear();
  });

  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'user-test',
        email: 'user@example.com',
        name: 'Demo User',
        role: 'USER'
      })
    });
  });

  await page.route('**/api/quote-drafts/current**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith('/items/part-gpu-chat') && route.request().method() === 'DELETE') {
      deleteRequests += 1;
      draft = emptyDraft;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(draft)
    });
  });

  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.includes('/price-history')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          partId: 'part-gpu-chat',
          partName: 'RTX 5070 챗봇 테스트',
          currentPrice: 890000,
          days: 3650,
          source: 'NAVER_SHOPPING_SEARCH',
          items: [],
          summary: {
            sampleCount: 0,
            currentPrice: 890000,
            minPrice: 890000,
            maxPrice: 890000,
            firstPrice: 890000,
            lastPrice: 890000,
            changeAmount: 0,
            changeRatePercent: 0
          }
        })
      });
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 })
    });
  });

  await page.route('**/api/ai/build-chat', async (route) => {
    const body = JSON.parse(route.request().postData() ?? '{}');
    buildChatBodies.push(body);
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        answerType: 'GENERAL',
        message: '현재 견적에서 GPU 제거 변경안을 만들었습니다.',
        builds: [],
        partRecommendation: null,
        actions: [
          {
            id: 'action-remove-gpu',
            type: 'REMOVE_DRAFT_PART',
            label: 'GPU 빼기',
            description: 'RTX 5070 챗봇 테스트를 견적에서 제거합니다.',
            payload: { partId: 'part-gpu-chat', category: 'GPU', source: 'AI_BUILD_CHAT' },
            requiresConfirmation: true
          }
        ],
        warnings: []
      })
    });
  });

  await page.goto('/self-quote');
  await expect(page.getByText('RTX 5070 챗봇 테스트')).toBeVisible();
  await page.getByRole('button', { name: 'AI 견적 챗봇 열기' }).click();
  await page.getByRole('textbox', { name: 'AI 챗봇에게 PC 사양 질문' }).fill('GPU 빼줘');
  await page.getByRole('button', { name: '질문 보내기' }).click();

  await expect.poll(() => buildChatBodies.length).toBe(1);
  expect((buildChatBodies[0] as { currentQuoteDraft?: { items?: Array<{ partId: string }> } }).currentQuoteDraft?.items?.[0]?.partId).toBe('part-gpu-chat');
  await expect(page.getByText('견적 장바구니 변경안')).toBeVisible();
  await page.getByRole('button', { name: '적용' }).click();

  await expect.poll(() => deleteRequests).toBe(1);
  await expect(page.getByText('왼쪽 목록에서 부품을 담으면 이곳에 내 견적이 쌓입니다.')).toBeVisible();
});

test('opens cooler internal assets from home category link', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
  });

  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    const category = url.searchParams.get('category') ?? '';
    const items = category === 'COOLER'
      ? [
          {
            id: 'part-cooler-home-test',
            category: 'COOLER',
            name: 'Liquid Freezer III 360 테스트',
            manufacturer: 'ARCTIC',
            price: 165000,
            status: 'ACTIVE',
            benchmarkSummary: { score: 77.7 },
            attributes: {
              shortSpec: '360mm AIO, AM5/LGA1851'
            },
            externalOffer: {
              imageUrl: 'https://example.test/cooler.png',
              supplierName: '쿨러테스트몰',
              offerUrl: 'https://example.test/cooler',
              lowPrice: 165000,
              source: 'NAVER_SHOPPING_SEARCH'
            }
          }
        ]
      : [];

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items,
        page: 0,
        size: 50,
        total: items.length
      })
    });
  });

  await page.goto('/');
  await page.getByRole('link', { name: '쿨러' }).click();

  await expect(page).toHaveURL('/self-quote?category=COOLER');
  await expect(page.getByText('쿨러 부품 목록')).toBeVisible();
  await expect(page.getByText('Liquid Freezer III 360 테스트')).toBeVisible();
  await expect(page.getByText('쿨러테스트몰')).toBeVisible();
  await expect(page.getByText('ACTIVE')).toHaveCount(0);
  await expect(page.getByText('77.7')).toHaveCount(0);
});

test('opens GPU internal assets from home category link', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
  });

  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    const category = url.searchParams.get('category') ?? '';
    const items = category === 'GPU'
      ? [
          {
            id: 'part-gpu-home-test',
            category: 'GPU',
            name: '홈에서 열린 RTX 테스트',
            manufacturer: 'NVIDIA',
            price: 890000,
            status: 'ACTIVE',
            benchmarkSummary: { score: 92.4 },
            externalOffer: {
              imageUrl: 'https://example.test/home-rtx.png',
              supplierName: '홈테스트몰',
              offerUrl: 'https://example.test/home-rtx',
              lowPrice: 890000,
              source: 'NAVER_SHOPPING_SEARCH'
            }
          }
        ]
      : [];

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items,
        page: 0,
        size: 50,
        total: items.length
      })
    });
  });

  await page.goto('/');
  await page.getByRole('link', { name: 'GPU', exact: true }).click();

  await expect(page).toHaveURL('/self-quote?category=GPU');
  await expect(page.getByText('GPU 부품 목록')).toBeVisible();
  await expect(page.getByText('홈에서 열린 RTX 테스트')).toBeVisible();
  await expect(page.getByText('홈테스트몰')).toBeVisible();
});

test('shows selected AI build separately from the manual quote draft and marks duplicate parts', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
    localStorage.setItem('buildgraph.authUser', JSON.stringify({
      id: 'user-test',
      email: 'user@example.com',
      name: 'Demo User',
      role: 'USER'
    }));
    sessionStorage.setItem('buildgraph.ai.selectedBuild:user-test', JSON.stringify({
      id: 'ai-balanced',
      tier: 'balanced',
      title: '균형 추천 조합',
      summary: 'QHD 게임과 개발을 함께 고려한 데모 조합입니다.',
      totalPrice: 1980000,
      appliedPartCategories: ['GPU'],
      selectedAt: '2026-06-30T09:00:00.000Z',
      items: [
        {
          partId: 'part-gpu-test',
          category: 'GPU',
          name: 'RTX 4070 SUPER 테스트',
          manufacturer: 'NVIDIA',
          quantity: 1,
          price: 890000,
          note: 'QHD 게임용 그래픽카드'
        },
        {
          partId: 'ai-cpu-balanced',
          category: 'CPU',
          name: 'Ryzen 7 AI 균형 CPU',
          manufacturer: 'AMD',
          quantity: 1,
          price: 420000,
          note: '게임과 개발 균형'
        }
      ]
    }));
  });

  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'draft-ai-panel-test',
        status: 'ACTIVE',
        name: '셀프 견적',
        items: [
          {
            id: 'draft-item-gpu-test',
            partId: 'part-gpu-test',
            category: 'GPU',
            name: 'RTX 4070 SUPER 테스트',
            manufacturer: 'NVIDIA',
            quantity: 1,
            unitPriceAtAdd: 890000,
            currentPrice: 890000,
            lineTotal: 890000,
            attributes: {}
          }
        ],
        totalPrice: 890000,
        itemCount: 1
      })
    });
  });

  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.includes('/price-history')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          partId: 'part-gpu-test',
          partName: 'RTX 4070 SUPER 테스트',
          currentPrice: 890000,
          days: 3650,
          source: 'NAVER_SHOPPING_SEARCH',
          items: [],
          summary: {
            sampleCount: 0,
            currentPrice: 890000,
            minPrice: 890000,
            maxPrice: 890000,
            firstPrice: 890000,
            lastPrice: 890000,
            changeAmount: 0,
            changeRatePercent: 0
          }
        })
      });
      return;
    }

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: [
          {
            id: 'part-gpu-test',
            category: 'GPU',
            name: 'RTX 4070 SUPER 테스트',
            manufacturer: 'NVIDIA',
            price: 890000,
            status: 'ACTIVE'
          }
        ],
        page: 0,
        size: 20,
        total: 1
      })
    });
  });

  await page.goto('/self-quote?category=GPU');

  const aiPanel = page.getByTestId('ai-selected-build-panel');
  await expect(aiPanel).toBeVisible();
  await expect(aiPanel.getByRole('heading', { name: 'AI 선택 조합' })).toBeVisible();
  await expect(aiPanel.getByText('균형 추천 조합')).toBeVisible();
  await expect(aiPanel.getByText('GPU 반영됨')).toBeVisible();
  await expect(aiPanel.getByText('실제 장바구니 적용 기록')).toBeVisible();
  await expect(aiPanel.getByText('이미 담김', { exact: true })).toBeVisible();
  await expect(aiPanel.getByText('별도 표시')).toBeVisible();
  await expect(page.getByRole('heading', { name: '견적 장바구니', exact: true })).toBeVisible();
  await expect(page.getByText('견적 합계')).toBeVisible();
});

test('paginates self quote assets in 20 item pages', async ({ page }) => {
  const requestedPages: string[] = [];
  const requestedSizes: string[] = [];

  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
  });

  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    const pageParam = url.searchParams.get('page') ?? '0';
    const sizeParam = url.searchParams.get('size') ?? '';
    requestedPages.push(pageParam);
    requestedSizes.push(sizeParam);

    const currentPage = Number.parseInt(pageParam, 10);
    const start = currentPage * 20;
    const items = Array.from({ length: 20 }, (_, index) => {
      const itemNumber = start + index + 1;
      return {
        id: `part-psu-page-${itemNumber}`,
        category: 'PSU',
        name: `페이징 파워 ${itemNumber}`,
        manufacturer: '테스트파워',
        price: 50000 + itemNumber,
        status: 'ACTIVE',
        attributes: {
          shortSpec: `${itemNumber}번 파워`
        },
        externalOffer: {
          imageUrl: `https://example.test/psu-${itemNumber}.png`,
          supplierName: '페이징몰',
          offerUrl: `https://example.test/psu-${itemNumber}`,
          lowPrice: 50000 + itemNumber,
          source: 'NAVER_SHOPPING_SEARCH'
        }
      };
    });

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items,
        page: currentPage,
        size: 20,
        total: 45
      })
    });
  });

  await page.goto('/self-quote?category=PSU');
  await expect(page.getByText('45개 중 1-20개 표시')).toBeVisible();
  await expect(page.getByText('페이지 1 / 3')).toBeVisible();
  await expect(page.getByText('페이징 파워 1', { exact: true })).toBeVisible();
  expect(requestedPages).toContain('0');
  expect(requestedSizes).toContain('20');

  await page.getByRole('button', { name: '다음' }).click();

  await expect(page).toHaveURL('/self-quote?category=PSU&page=1');
  await expect(page.getByText('45개 중 21-40개 표시')).toBeVisible();
  await expect(page.getByText('페이지 2 / 3')).toBeVisible();
  await expect(page.getByText('페이징 파워 21', { exact: true })).toBeVisible();
  expect(requestedPages).toContain('1');
});

test('keeps self quote shopping workspace usable on mobile width', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
  });

  await page.route('**/api/quote-drafts/current**', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'draft-mobile-test',
        status: 'ACTIVE',
        name: '셀프 견적',
        items: [
          {
            id: 'draft-item-mobile-test',
            partId: 'part-mobile-gpu-test',
            category: 'GPU',
            name: '모바일 RTX 테스트',
            manufacturer: 'NVIDIA',
            quantity: 1,
            unitPriceAtAdd: 890000,
            currentPrice: 890000,
            lineTotal: 890000,
            attributes: {}
          }
        ],
        totalPrice: 890000,
        itemCount: 1
      })
    });
  });

  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.includes('/price-history')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          partId: 'part-mobile-gpu-test',
          partName: '모바일 RTX 테스트',
          currentPrice: 890000,
          days: 3650,
          source: 'NAVER_SHOPPING_SEARCH',
          items: [{ price: 890000, source: 'NAVER_SHOPPING_SEARCH', collectedAt: '2026-06-29T00:00:00Z' }],
          summary: {
            sampleCount: 1,
            currentPrice: 890000,
            minPrice: 890000,
            maxPrice: 890000,
            firstPrice: 890000,
            lastPrice: 890000,
            changeAmount: 0,
            changeRatePercent: 0
          }
        })
      });
      return;
    }

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        items: [
          {
            id: 'part-mobile-gpu-test',
            category: 'GPU',
            name: '모바일 RTX 테스트',
            manufacturer: 'NVIDIA',
            price: 890000,
            status: 'ACTIVE',
            attributes: { shortSpec: 'QHD gaming mobile test' },
            externalOffer: {
              imageUrl: 'https://example.test/mobile-gpu.png',
              supplierName: '모바일테스트몰',
              offerUrl: 'https://example.test/mobile-gpu',
              lowPrice: 890000,
              source: 'NAVER_SHOPPING_SEARCH'
            }
          }
        ],
        page: 0,
        size: 20,
        total: 1
      })
    });
  });

  await page.goto('/self-quote?category=GPU');

  await expect(page.getByText('GPU 부품 목록')).toBeVisible();
  await expect(page.getByRole('link', { name: '모바일 RTX 테스트', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: '견적 장바구니', exact: true })).toBeVisible();

  const hasBodyOverflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
  expect(hasBodyOverflow).toBe(false);
});

test('updates quantity only for repeatable quote draft categories', async ({ page }) => {
  let ramQuantity = 1;
  const ramItem = {
    id: 'draft-item-ram-test',
    partId: 'part-ram-quantity-test',
    category: 'RAM',
    name: '삼성 DDR5 32GB 테스트',
    manufacturer: 'Samsung',
    quantity: ramQuantity,
    unitPriceAtAdd: 120000,
    currentPrice: 120000,
    lineTotal: 120000,
    attributes: {}
  };
  const cpuItem = {
    id: 'draft-item-cpu-test',
    partId: 'part-cpu-quantity-test',
    category: 'CPU',
    name: 'Ryzen 9 수량고정 테스트',
    manufacturer: 'AMD',
    quantity: 1,
    unitPriceAtAdd: 820000,
    currentPrice: 820000,
    lineTotal: 820000,
    attributes: {}
  };

  const draft = () => ({
    id: 'draft-quantity-test',
    status: 'ACTIVE',
    name: '셀프 견적',
    items: [
      { ...ramItem, quantity: ramQuantity, lineTotal: ramQuantity * ramItem.currentPrice },
      cpuItem
    ],
    totalPrice: ramQuantity * ramItem.currentPrice + cpuItem.currentPrice,
    itemCount: ramQuantity + 1
  });

  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
  });

  await page.route('**/api/quote-drafts/current**', async (route) => {
    if (route.request().method() === 'PATCH') {
      const body = JSON.parse(route.request().postData() ?? '{}') as { quantity?: number };
      ramQuantity = body.quantity ?? ramQuantity;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(draft())
    });
  });

  await page.route('**/api/parts**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.includes('/price-history')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          partId: 'part-ram-quantity-test',
          partName: '삼성 DDR5 32GB 테스트',
          currentPrice: 120000,
          days: 3650,
          source: 'NAVER_SHOPPING_SEARCH',
          items: [{ price: 120000, source: 'NAVER_SHOPPING_SEARCH', collectedAt: '2026-06-29T00:00:00Z' }],
          summary: {
            sampleCount: 1,
            currentPrice: 120000,
            minPrice: 120000,
            maxPrice: 120000,
            firstPrice: 120000,
            lastPrice: 120000,
            changeAmount: 0,
            changeRatePercent: 0
          }
        })
      });
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ items: [], page: 0, size: 20, total: 0 })
    });
  });

  await page.goto('/self-quote');

  await expect(page.getByText('삼성 DDR5 32GB 테스트')).toBeVisible();
  await expect(page.getByLabel('삼성 DDR5 32GB 테스트 수량 선택')).toBeVisible();
  await expect(page.getByLabel('Ryzen 9 수량고정 테스트 수량 선택')).toHaveCount(0);
  await expect(page.getByText('수량 1개')).toHaveCount(2);

  await page.getByRole('button', { name: '삼성 DDR5 32GB 테스트 수량 증가' }).click();

  await expect(page.getByText('수량 2개')).toBeVisible();
  await expect(page.getByText('1,060,000원')).toBeVisible();
});

test('shows price trend chart on product detail page', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('buildgraph.token', 'jwt-user-token');
  });

  await page.route('**/api/auth/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'user-test',
        email: 'user@example.com',
        name: 'Demo User',
        role: 'USER'
      })
    });
  });

  await page.route('**/api/parts/part-gpu-trend-test**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith('/price-history')) {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          partId: 'part-gpu-trend-test',
          partName: '가격 추이 RTX 테스트',
          currentPrice: 950000,
          days: 3650,
          source: 'NAVER_SHOPPING_SEARCH',
          items: [
            { price: 1020000, source: 'NAVER_SHOPPING_SEARCH', collectedAt: '2026-06-27T00:00:00Z' },
            { price: 990000, source: 'NAVER_SHOPPING_SEARCH', collectedAt: '2026-06-28T00:00:00Z' },
            { price: 950000, source: 'NAVER_SHOPPING_SEARCH', collectedAt: '2026-06-29T00:00:00Z' }
          ],
          summary: {
            sampleCount: 3,
            currentPrice: 950000,
            minPrice: 950000,
            maxPrice: 1020000,
            firstPrice: 1020000,
            lastPrice: 950000,
            changeAmount: -70000,
            changeRatePercent: -6.86
          }
        })
      });
      return;
    }

    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'part-gpu-trend-test',
        category: 'GPU',
        name: '가격 추이 RTX 테스트',
        manufacturer: 'NVIDIA',
        price: 950000,
        status: 'ACTIVE',
        latestPriceCollectedAt: '2026-06-29T00:00:00Z',
        attributes: {
          shortSpec: 'RTX price trend test',
          vramGb: 12,
          wattage: 220
        },
        externalOffer: {
          imageUrl: 'https://example.test/trend-gpu.png',
          supplierName: '가격테스트몰',
          offerUrl: 'https://example.test/trend-gpu',
          lowPrice: 950000,
          source: 'NAVER_SHOPPING_SEARCH'
        }
      })
    });
  });

  await page.goto('/parts/part-gpu-trend-test');

  await expect(page.getByRole('heading', { name: '가격 추이 RTX 테스트' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '주요 스펙' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '가격 변동 추이' })).toBeVisible();
  await expect(page.getByRole('img', { name: '가격 변동 추이 그래프' })).toBeVisible();
  await expect(page.getByText('950,000원').first()).toBeVisible();
  await expect(page.getByText('-70,000원 (-6.86%)')).toBeVisible();
  await expect(page.getByText('전체 내부 스펙')).toHaveCount(0);
});

test('goes home after login from product detail redirect', async ({ page }) => {
  let savedToDraft = false;

  await page.route('**/api/parts/part-gpu-detail-test', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'part-gpu-detail-test',
        category: 'GPU',
        name: '상세 담기 RTX 테스트',
        manufacturer: 'NVIDIA',
        price: 1200000,
        status: 'ACTIVE',
        attributes: {
          shortSpec: 'RTX detail save test',
          toolReady: true
        },
        externalOffer: {
          imageUrl: 'https://example.test/detail-gpu.png',
          supplierName: '상세테스트몰',
          offerUrl: 'https://example.test/detail-gpu',
          lowPrice: 1200000,
          source: 'NAVER_SHOPPING_SEARCH'
        }
      })
    });
  });

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
        refreshToken: 'demo-refresh-user',
        user: {
          id: 'user-test',
          email: 'user@example.com',
          name: 'Demo User',
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
        id: 'user-test',
        email: 'user@example.com',
        name: 'Demo User',
        role: 'USER'
      })
    });
  });

  await page.route('**/api/quote-drafts/current/items/part-gpu-detail-test', async (route) => {
    expect(route.request().method()).toBe('PUT');
    expect(route.request().headers().authorization).toBe('Bearer jwt-user-token');
    savedToDraft = true;
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'draft-detail-test',
        status: 'ACTIVE',
        name: '셀프 견적',
        items: [
          {
            id: 'draft-item-detail-test',
            partId: 'part-gpu-detail-test',
            category: 'GPU',
            name: '상세 담기 RTX 테스트',
            quantity: 1,
            currentPrice: 1200000,
            lineTotal: 1200000
          }
        ],
        totalPrice: 1200000,
        itemCount: 1
      })
    });
  });

  await page.goto('/parts/part-gpu-detail-test');
  await expect(page).toHaveURL('/login?redirect=%2Fparts%2Fpart-gpu-detail-test');
  await page.getByLabel('이메일').fill('user@example.com');
  await page.getByLabel('비밀번호').fill('passw0rd!');
  await page.getByRole('button', { name: '로그인' }).click();

  await expect(page).toHaveURL('/');
  expect(await page.evaluate(() => localStorage.getItem('buildgraph.refreshToken'))).toBe('demo-refresh-user');
  await expect(page.getByText('로그인됨 · user@example.com · USER')).toBeVisible();
  await expect(page.getByText('Demo User')).toBeVisible();
  await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible();

  expect(savedToDraft).toBe(false);
});
