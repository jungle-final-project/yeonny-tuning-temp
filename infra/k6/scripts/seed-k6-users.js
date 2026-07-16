const fs = require('fs');
const path = require('path');

const envPath = process.argv[2] || path.join(__dirname, '..', '.env.local');

if (fs.existsSync(envPath)) {
  process.loadEnvFile(envPath);
}

const baseUrl = (process.env.BASE_URL || process.env.NONE_BASE_URL || 'http://localhost:8080')
  .replace(/\/$/, '');
const accountCount = positiveInteger(process.env.TEST_USER_COUNT, 300);
const startIndex = positiveInteger(process.env.TEST_USER_START_INDEX, 1);
const concurrency = positiveInteger(process.env.SEED_CONCURRENCY, 10);
const emailTemplate = process.env.TEST_USER_EMAIL_TEMPLATE || 'k6-user-{vu}@example.com';
const password = process.env.TEST_USER_PASSWORD || 'passw0rd!';

const results = {
  created: 0,
  duplicate: 0,
  failed: 0,
};

function positiveInteger(value, fallback) {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
}

function emailFor(accountIndex) {
  return emailTemplate.replace('{vu}', String(accountIndex));
}

async function createAccount(accountIndex) {
  const email = emailFor(accountIndex);
  const response = await fetch(`${baseUrl}/api/users`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      name: `k6-user-${accountIndex}`,
      email,
      password,
      phoneNumber: '010-0000-0000',
      postalCode: '06236',
      addressLine1: 'k6 테스트 주소',
      addressLine2: String(accountIndex),
      termsAccepted: true,
      marketingAccepted: false,
    }),
  });

  if (response.ok) {
    results.created += 1;
    console.log(`[created] ${email}`);
    return;
  }

  const bodyText = await response.text();
  let errorCode;
  try {
    errorCode = JSON.parse(bodyText).code;
  } catch {
    errorCode = null;
  }

  if (errorCode === 'DUPLICATE_RESOURCE') {
    results.duplicate += 1;
    console.log(`[skip] ${email} already exists`);
    return;
  }

  results.failed += 1;
  console.error(`[failed] ${email}: status=${response.status}, body=${bodyText}`);
}

async function worker(accountIndexes) {
  while (accountIndexes.length > 0) {
    const accountIndex = accountIndexes.shift();
    try {
      await createAccount(accountIndex);
    } catch (error) {
      results.failed += 1;
      console.error(`[failed] ${emailFor(accountIndex)}: ${error.message}`);
    }
  }
}

async function main() {
  const accountIndexes = Array.from(
    { length: accountCount },
    (_, offset) => startIndex + offset,
  );
  const workerCount = Math.min(concurrency, accountIndexes.length);

  console.log(
    `Seeding ${accountCount} k6 users to ${baseUrl} `
      + `(start=${startIndex}, concurrency=${workerCount})`,
  );

  await Promise.all(
    Array.from({ length: workerCount }, () => worker(accountIndexes)),
  );

  console.log(
    `Done: created=${results.created}, duplicate=${results.duplicate}, failed=${results.failed}`,
  );

  if (results.failed > 0) {
    process.exitCode = 1;
  }
}

main();

