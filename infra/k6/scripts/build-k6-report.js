const fs = require('fs');
const path = require('path');

const inputPath = process.argv[2] || path.join('infra', 'k6', 'results', 'self-quote.ndjson');
const outputPath = process.argv[3] || path.join('infra', 'k6', 'reports', 'self-quote-report.html');
const bucketMs = Number(process.env.K6_BUCKET_MS || 10000);
const variants = ['none', 'caffeine'];

if (!fs.existsSync(inputPath)) {
  console.error(`k6 result file not found: ${inputPath}`);
  process.exit(1);
}

function cacheVariant(tags = {}) {
  if (variants.includes(tags.cache)) {
    return tags.cache;
  }
  if (tags.scenario === 'cache_none') {
    return 'none';
  }
  if (tags.scenario === 'cache_caffeine') {
    return 'caffeine';
  }
  return null;
}

const pointsByVariant = new Map(variants.map((variant) => [variant, []]));

for (const line of fs.readFileSync(inputPath, 'utf8').split(/\r?\n/)) {
  if (!line.trim()) {
    continue;
  }

  let row;
  try {
    row = JSON.parse(line);
  } catch {
    continue;
  }

  if (row.type !== 'Point' || row.metric !== 'http_req_duration') {
    continue;
  }

  const tags = row.data && row.data.tags;
  const variant = cacheVariant(tags);
  const value = Number(row.data && row.data.value);
  const time = Date.parse(row.data && row.data.time);

  if (variant && Number.isFinite(value) && Number.isFinite(time)) {
    pointsByVariant.get(variant).push({ time, value });
  }
}

for (const variant of variants) {
  if (pointsByVariant.get(variant).length === 0) {
    console.error(`http_req_duration points not found for cache=${variant}.`);
    console.error('Run k6 with scenario tags cache=none and cache=caffeine.');
    process.exit(1);
  }
  pointsByVariant.get(variant).sort((a, b) => a.time - b.time);
}

function percentile(sorted, ratio) {
  if (sorted.length === 0) {
    return 0;
  }
  const index = Math.min(sorted.length - 1, Math.ceil(sorted.length * ratio) - 1);
  return sorted[index];
}

function round(value) {
  return Math.round(value * 100) / 100;
}

function summarize(values) {
  const sorted = [...values].sort((a, b) => a - b);
  return {
    count: values.length,
    median: round(percentile(sorted, 0.5)),
    p95: round(percentile(sorted, 0.95)),
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
    .map(([bucket, values]) => ({
      second: Math.round(bucket / 1000),
      ...summarize(values),
    }));
}

const seriesByVariant = Object.fromEntries(
  variants.map((variant) => [variant, buildSeries(pointsByVariant.get(variant))])
);
const overallByVariant = Object.fromEntries(
  variants.map((variant) => [
    variant,
    summarize(pointsByVariant.get(variant).map((point) => point.value)),
  ])
);

const seconds = Array.from(new Set(
  variants.flatMap((variant) => seriesByVariant[variant].map((row) => row.second))
)).sort((a, b) => a - b);

function rowsBySecond(series) {
  return Object.fromEntries(series.map((row) => [row.second, row]));
}

const noneRows = rowsBySecond(seriesByVariant.none);
const caffeineRows = rowsBySecond(seriesByVariant.caffeine);
const tableRows = seconds.map((second) => ({
  second,
  none: noneRows[second] || null,
  caffeine: caffeineRows[second] || null,
}));

const html = `<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>k6 Cache Comparison Report</title>
  <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
  <style>
    body { font-family: Arial, sans-serif; margin: 32px; color: #172033; }
    .summary { display: flex; gap: 12px; margin: 20px 0; flex-wrap: wrap; }
    .metric { border: 1px solid #d9deea; border-radius: 8px; padding: 12px 16px; min-width: 150px; }
    .metric strong { display: block; font-size: 20px; margin-top: 4px; }
    .none { border-left: 4px solid #dc2626; }
    .caffeine { border-left: 4px solid #2563eb; }
    .chart-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px; }
    .chart-card { border: 1px solid #d9deea; border-radius: 10px; padding: 16px; min-width: 0; }
    .chart-card h2 { margin: 0 0 12px; font-size: 18px; }
    .chart-card canvas { width: 100% !important; height: 420px !important; }
    @media (max-width: 900px) {
      .chart-grid { grid-template-columns: 1fr; }
    }
    table { border-collapse: collapse; width: 100%; margin-top: 24px; font-size: 14px; }
    th, td { border-bottom: 1px solid #e6e9f0; padding: 8px 10px; text-align: right; }
    th:first-child, td:first-child { text-align: left; }
    .none-heading { color: #dc2626; }
    .caffeine-heading { color: #2563eb; }
  </style>
</head>
<body>
  <h1>k6 Cache Comparison Report</h1>
  <p>각 시나리오 시작을 0초로 정규화 · Bucket: ${bucketMs / 1000}s · Source: ${inputPath}</p>
  <div class="summary">
    <div class="metric none"><span>캐싱 없음 요청</span><strong>${overallByVariant.none.count}</strong></div>
    <div class="metric none"><span>캐싱 없음 Median</span><strong>${overallByVariant.none.median} ms</strong></div>
    <div class="metric none"><span>캐싱 없음 p95</span><strong>${overallByVariant.none.p95} ms</strong></div>
    <div class="metric caffeine"><span>캐싱 있음 요청</span><strong>${overallByVariant.caffeine.count}</strong></div>
    <div class="metric caffeine"><span>캐싱 있음 Median</span><strong>${overallByVariant.caffeine.median} ms</strong></div>
    <div class="metric caffeine"><span>캐싱 있음 p95</span><strong>${overallByVariant.caffeine.p95} ms</strong></div>
  </div>
  <div class="chart-grid">
    <section class="chart-card">
      <h2>Median 응답시간</h2>
      <canvas id="medianLatency"></canvas>
    </section>
    <section class="chart-card">
      <h2>p95 응답시간</h2>
      <canvas id="p95Latency"></canvas>
    </section>
  </div>
  <table>
    <thead>
      <tr>
        <th rowspan="2">Time</th>
        <th colspan="3" class="none-heading">캐싱 없음</th>
        <th colspan="3" class="caffeine-heading">캐싱 있음</th>
      </tr>
      <tr>
        <th>Count</th><th>Median ms</th><th>p95 ms</th>
        <th>Count</th><th>Median ms</th><th>p95 ms</th>
      </tr>
    </thead>
    <tbody>
      ${tableRows.map((row) => `<tr><td>${row.second}s</td><td>${row.none?.count ?? '-'}</td><td>${row.none?.median ?? '-'}</td><td>${row.none?.p95 ?? '-'}</td><td>${row.caffeine?.count ?? '-'}</td><td>${row.caffeine?.median ?? '-'}</td><td>${row.caffeine?.p95 ?? '-'}</td></tr>`).join('\n')}
    </tbody>
  </table>
  <script>
    const labels = ${JSON.stringify(seconds)};
    const noneRows = ${JSON.stringify(noneRows)};
    const caffeineRows = ${JSON.stringify(caffeineRows)};
    const values = (rows, field) => labels.map((second) => rows[second]?.[field] ?? null);

    const commonOptions = (title) => ({
      responsive: true,
      maintainAspectRatio: false,
      interaction: { mode: 'index', intersect: false },
      plugins: {
        title: { display: true, text: title },
      },
      scales: {
        x: { title: { display: true, text: '시나리오 시작 후 경과 시간' } },
        y: { beginAtZero: true, title: { display: true, text: '응답시간 (ms)' } },
      },
    });

    new Chart(document.getElementById('medianLatency'), {
      type: 'line',
      data: {
        labels: labels.map((second) => second + 's'),
        datasets: [
          {
            label: '캐싱 없음 Median',
            data: values(noneRows, 'median'),
            borderColor: '#dc2626',
            backgroundColor: '#dc2626',
            tension: 0.2,
            spanGaps: false,
          },
          {
            label: '캐싱 있음 Median',
            data: values(caffeineRows, 'median'),
            borderColor: '#2563eb',
            backgroundColor: '#2563eb',
            tension: 0.2,
            spanGaps: false,
          },
        ],
      },
      options: commonOptions('캐싱 없음 vs 캐싱 있음 Median'),
    });

    new Chart(document.getElementById('p95Latency'), {
      type: 'line',
      data: {
        labels: labels.map((second) => second + 's'),
        datasets: [
          {
            label: '캐싱 없음 p95',
            data: values(noneRows, 'p95'),
            borderColor: '#f87171',
            backgroundColor: '#f87171',
            tension: 0.2,
            spanGaps: false,
          },
          {
            label: '캐싱 있음 p95',
            data: values(caffeineRows, 'p95'),
            borderColor: '#60a5fa',
            backgroundColor: '#60a5fa',
            tension: 0.2,
            spanGaps: false,
          },
        ],
      },
      options: commonOptions('캐싱 없음 vs 캐싱 있음 p95'),
    });
  </script>
</body>
</html>`;

fs.mkdirSync(path.dirname(outputPath), { recursive: true });
fs.writeFileSync(outputPath, html);
console.log(`report written: ${outputPath}`);
