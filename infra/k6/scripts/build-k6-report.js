const fs = require('fs');
const path = require('path');

const inputPath = process.argv[2] || path.join('infra', 'k6', 'results', 'self-quote.ndjson');
const outputPath = process.argv[3] || path.join('infra', 'k6', 'reports', 'self-quote-report.html');
const bucketMs = Number(process.env.K6_BUCKET_MS || 10000);
const requestedVariants = String(process.env.K6_VARIANTS || '')
  .split(',')
  .map((value) => value.trim())
  .filter(Boolean);

if (!fs.existsSync(inputPath)) {
  console.error(`k6 result file not found: ${inputPath}`);
  process.exit(1);
}

function variantFrom(tags = {}) {
  return tags.cache || tags.variant || tags.scenario || 'baseline';
}

function endpointFrom(tags = {}) {
  return tags.endpoint || tags.phase || 'unknown';
}

const pointsByVariant = new Map();
const pointsByVariantEndpoint = new Map();
for (const line of fs.readFileSync(inputPath, 'utf8').split(/\r?\n/)) {
  if (!line.trim()) continue;
  let row;
  try { row = JSON.parse(line); } catch (_) { continue; }
  if (row.type !== 'Point' || row.metric !== 'http_req_duration') continue;

  const tags = row.data && row.data.tags;
  const variant = variantFrom(tags);
  const endpoint = endpointFrom(tags);
  const value = Number(row.data && row.data.value);
  const time = Date.parse(row.data && row.data.time);
  if (!Number.isFinite(value) || !Number.isFinite(time)) continue;
  if (!pointsByVariant.has(variant)) pointsByVariant.set(variant, []);
  pointsByVariant.get(variant).push({ time, value });
  if (!pointsByVariantEndpoint.has(variant)) pointsByVariantEndpoint.set(variant, new Map());
  const endpointMap = pointsByVariantEndpoint.get(variant);
  if (!endpointMap.has(endpoint)) endpointMap.set(endpoint, []);
  endpointMap.get(endpoint).push({ time, value });
}

const variants = requestedVariants.length > 0 ? requestedVariants : Array.from(pointsByVariant.keys()).sort();
if (variants.length === 0) {
  console.error('http_req_duration points not found. Run k6 with --out json=<path>.');
  process.exit(1);
}
for (const variant of variants) {
  const points = pointsByVariant.get(variant) || [];
  if (points.length === 0) {
    console.error(`http_req_duration points not found for variant=${variant}.`);
    process.exit(1);
  }
  points.sort((a, b) => a.time - b.time);
}

function percentile(sorted, ratio) {
  if (sorted.length === 0) return 0;
  return sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * ratio) - 1)];
}

function summarize(values) {
  const sorted = [...values].sort((a, b) => a - b);
  const total = values.reduce((sum, value) => sum + value, 0);
  return {
    count: values.length,
    avg: values.length ? Math.round((total / values.length) * 100) / 100 : 0,
    median: Math.round(percentile(sorted, 0.5) * 100) / 100,
    p90: Math.round(percentile(sorted, 0.9) * 100) / 100,
    p95: Math.round(percentile(sorted, 0.95) * 100) / 100,
    max: Math.round((sorted[sorted.length - 1] || 0) * 100) / 100,
  };
}

function buildSeries(points) {
  const startedAt = points[0].time;
  const buckets = new Map();
  for (const point of points) {
    const bucket = Math.floor((point.time - startedAt) / bucketMs) * bucketMs;
    const values = buckets.get(bucket) || [];
    values.push(point.value);
    buckets.set(bucket, values);
  }
  return Array.from(buckets.entries())
    .sort((a, b) => a[0] - b[0])
    .map(([bucket, values]) => ({ second: Math.round(bucket / 1000), ...summarize(values) }));
}

const seriesByVariant = Object.fromEntries(variants.map((variant) => [variant, buildSeries(pointsByVariant.get(variant))]));
const overallByVariant = Object.fromEntries(variants.map((variant) => [
  variant,
  summarize(pointsByVariant.get(variant).map((point) => point.value)),
]));
const endpointSummariesByVariant = Object.fromEntries(variants.map((variant) => {
  const endpointMap = pointsByVariantEndpoint.get(variant) || new Map();
  const rows = Array.from(endpointMap.entries())
    .map(([endpoint, points]) => ({ endpoint, ...summarize(points.map((point) => point.value)) }))
    .sort((a, b) => b.p95 - a.p95 || b.count - a.count || a.endpoint.localeCompare(b.endpoint));
  return [variant, rows];
}));
const seconds = Array.from(new Set(variants.flatMap((variant) => seriesByVariant[variant].map((row) => row.second))))
  .sort((a, b) => a - b);
const rowsByVariant = Object.fromEntries(variants.map((variant) => [
  variant,
  Object.fromEntries(seriesByVariant[variant].map((row) => [row.second, row])),
]));
const colors = ['#2563eb', '#dc2626', '#059669', '#9333ea', '#ea580c', '#0891b2'];

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}

function titleToken(token) {
  const knownTokens = {
    api: 'API',
    auth: 'Auth',
    async: 'Async',
    d2: 'D2',
    db: 'DB',
    k6: 'k6',
    local: 'Local',
    none: 'None',
    p95: 'p95',
    swr: 'SWR',
    ttl: 'TTL',
  };
  const lowerToken = token.toLowerCase();
  if (knownTokens[lowerToken]) return knownTokens[lowerToken];
  if (/^stage\d+$/i.test(token)) return token.replace(/^stage/i, 'Stage');
  if (/^\d+$/.test(token)) return token;
  return lowerToken.charAt(0).toUpperCase() + lowerToken.slice(1);
}

function reportTitleFromPath(resultPath) {
  const explicitTitle = String(process.env.K6_REPORT_TITLE || '').trim();
  if (explicitTitle) return explicitTitle;
  const stem = path.basename(resultPath, path.extname(resultPath)).replace(/-report$/i, '');
  const title = stem
    .split(/[-_]+/)
    .filter(Boolean)
    .map(titleToken)
    .join(' ');
  return `BuildGraph k6 ${title || 'Load Test'} Report`;
}

const reportTitle = reportTitleFromPath(inputPath);

function datasets(field) {
  return variants.map((variant, index) => ({
    label: `${variant} ${field === 'median' ? 'Median' : 'p95'}`,
    data: seconds.map((second) => rowsByVariant[variant][second]?.[field] ?? null),
    borderColor: colors[index % colors.length],
    backgroundColor: colors[index % colors.length],
    tension: 0.2,
    spanGaps: false,
  }));
}

function endpointSummaryHtml() {
  return variants.map((variant) => {
    const rows = endpointSummariesByVariant[variant] || [];
    return `<section class="endpoint-card">
      <h3>${escapeHtml(variant)}</h3>
      <table>
        <thead><tr><th>Endpoint</th><th>Count</th><th>Avg ms</th><th>Median ms</th><th>p90 ms</th><th>p95 ms</th><th>Max ms</th></tr></thead>
        <tbody>${rows.map((row) => `<tr><td>${escapeHtml(row.endpoint)}</td><td>${row.count}</td><td>${row.avg}</td><td>${row.median}</td><td>${row.p90}</td><td>${row.p95}</td><td>${row.max}</td></tr>`).join('\n')}</tbody>
      </table>
    </section>`;
  }).join('\n');
}

const html = `<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>${escapeHtml(reportTitle)}</title>
  <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
  <style>
    body { font-family: Aptos, "Segoe UI", sans-serif; margin: 32px; color: #172033; }
    .summary { display: flex; gap: 12px; margin: 20px 0; flex-wrap: wrap; }
    .metric { border: 1px solid #d9deea; border-radius: 8px; padding: 12px 16px; min-width: 180px; }
    .metric strong { display: block; font-size: 20px; margin: 4px 0; }
    .chart-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px; }
    .chart-card { border: 1px solid #d9deea; border-radius: 10px; padding: 16px; min-width: 0; }
    .chart-card canvas { width: 100% !important; height: 420px !important; }
    .endpoint-section { margin-top: 28px; }
    .endpoint-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(520px, 1fr)); gap: 16px; }
    .endpoint-card { border: 1px solid #d9deea; border-radius: 10px; padding: 16px; overflow-x: auto; }
    .endpoint-card h3 { margin: 0 0 8px; }
    table { border-collapse: collapse; width: 100%; margin-top: 24px; font-size: 14px; }
    th, td { border-bottom: 1px solid #e6e9f0; padding: 8px 10px; text-align: right; }
    th:first-child, td:first-child { text-align: left; }
    @media (max-width: 900px) { .chart-grid { grid-template-columns: 1fr; } }
  </style>
</head>
<body>
  <h1>${escapeHtml(reportTitle)}</h1>
  <p>각 variant 시작을 0초로 정규화 · Bucket: ${bucketMs / 1000}s · Source: ${escapeHtml(inputPath)}</p>
  <div class="summary">
    ${variants.map((variant) => `<div class="metric"><span>${escapeHtml(variant)}</span><strong>${overallByVariant[variant].count} requests</strong><div>Median ${overallByVariant[variant].median} ms · p95 ${overallByVariant[variant].p95} ms</div></div>`).join('\n')}
  </div>
  <div class="chart-grid">
    <section class="chart-card"><h2>Median 응답시간</h2><canvas id="medianLatency"></canvas></section>
    <section class="chart-card"><h2>p95 응답시간</h2><canvas id="p95Latency"></canvas></section>
  </div>
  <section class="endpoint-section">
    <h2>Endpoint Summary</h2>
    <p>Endpoint tag 기준으로 HTTP latency를 분리합니다. Login API는 <code>auth_login</code>으로 보입니다.</p>
    <div class="endpoint-grid">${endpointSummaryHtml()}</div>
  </section>
  <table>
    <thead><tr><th>Time</th>${variants.map((variant) => `<th colspan="3">${escapeHtml(variant)}</th>`).join('')}</tr>
    <tr><th></th>${variants.map(() => '<th>Count</th><th>Median ms</th><th>p95 ms</th>').join('')}</tr></thead>
    <tbody>${seconds.map((second) => `<tr><td>${second}s</td>${variants.map((variant) => {
      const row = rowsByVariant[variant][second];
      return `<td>${row?.count ?? '-'}</td><td>${row?.median ?? '-'}</td><td>${row?.p95 ?? '-'}</td>`;
    }).join('')}</tr>`).join('\n')}</tbody>
  </table>
  <script>
    const labels = ${JSON.stringify(seconds)}.map((second) => second + 's');
    const commonOptions = (title) => ({
      responsive: true, maintainAspectRatio: false, interaction: { mode: 'index', intersect: false },
      plugins: { title: { display: true, text: title } },
      scales: {
        x: { title: { display: true, text: 'variant 시작 후 경과 시간' } },
        y: { beginAtZero: true, title: { display: true, text: '응답시간 (ms)' } },
      },
    });
    new Chart(document.getElementById('medianLatency'), {
      type: 'line', data: { labels, datasets: ${JSON.stringify(datasets('median'))} },
      options: commonOptions('Median 응답시간'),
    });
    new Chart(document.getElementById('p95Latency'), {
      type: 'line', data: { labels, datasets: ${JSON.stringify(datasets('p95'))} },
      options: commonOptions('p95 응답시간'),
    });
  </script>
</body>
</html>`;

fs.mkdirSync(path.dirname(outputPath), { recursive: true });
fs.writeFileSync(outputPath, html);
console.log(`report written: ${outputPath}`);
