const fs = require('fs');
const path = require('path');

const inputPath = process.argv[2] || path.join('infra', 'k6', 'results', 'self-quote-breakpoint.ndjson');
const outputPath = process.argv[3] || path.join('infra', 'k6', 'reports', 'self-quote-breakpoint-report.html');
const bucketMs = 20_000;
const bucketSeconds = bucketMs / 1000;
const reportDurationMs = 120_000;

if (!fs.existsSync(inputPath)) {
  console.error(`k6 result file not found: ${inputPath}`);
  process.exit(1);
}

const points = [];
for (const line of fs.readFileSync(inputPath, 'utf8').split(/\r?\n/)) {
  if (!line.trim()) continue;

  let row;
  try {
    row = JSON.parse(line);
  } catch (_) {
    continue;
  }

  if (row.type !== 'Point') continue;
  if (!['http_req_duration', 'http_req_failed', 'http_reqs', 'dropped_iterations'].includes(row.metric)) continue;

  const time = Date.parse(row.data && row.data.time);
  const value = Number(row.data && row.data.value);
  if (!Number.isFinite(time) || !Number.isFinite(value)) continue;
  points.push({
    metric: row.metric,
    time,
    value,
    scenario: row.data && row.data.tags && row.data.tags.scenario,
  });
}

if (!points.some((point) => point.metric === 'http_req_duration')) {
  console.error('http_req_duration points not found. Run k6 with --out json=<path>.');
  process.exit(1);
}

const scenarioPoints = points.filter((point) => point.scenario === 'breakpoint_none');
const targetPoints = scenarioPoints.length > 0 ? scenarioPoints : points;
const startedAt = targetPoints.reduce(
  (minimum, point) => Math.min(minimum, point.time),
  Infinity,
);
const bucketCount = Math.ceil(reportDurationMs / bucketMs);
const buckets = Array.from({ length: bucketCount }, (_, index) => ({
  second: index * bucketSeconds,
  durations: [],
  failedCount: 0,
  requestCount: 0,
  httpRequests: 0,
  dropped: 0,
}));

for (const point of points) {
  const elapsedMs = point.time - startedAt;
  if (elapsedMs < 0 || elapsedMs >= reportDurationMs) continue;
  const bucketIndex = Math.floor(elapsedMs / bucketMs);
  const bucket = buckets[bucketIndex];

  if (point.metric === 'http_req_duration') bucket.durations.push(point.value);
  if (point.metric === 'http_req_failed') {
    bucket.requestCount += 1;
    bucket.failedCount += point.value;
  }
  if (point.metric === 'http_reqs') bucket.httpRequests += point.value;
  if (point.metric === 'dropped_iterations') bucket.dropped += point.value;
}

function percentile(values, ratio) {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * ratio) - 1)];
}

function round(value, digits = 2) {
  if (value === null) return null;
  const unit = 10 ** digits;
  return Math.round(value * unit) / unit;
}

const series = buckets.map((bucket) => ({
  second: bucket.second,
  p95: round(percentile(bucket.durations, 0.95)),
  median: round(percentile(bucket.durations, 0.5)),
  rps: round(bucket.httpRequests / (bucketMs / 1000)),
  dropped: Math.round(bucket.dropped),
  failedRate: round(bucket.requestCount === 0 ? 0 : (bucket.failedCount / bucket.requestCount) * 100),
}));

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}

const totalDropped = series.reduce((sum, row) => sum + row.dropped, 0);
const peakP95 = Math.max(0, ...series.map((row) => row.p95 || 0));
const peakRps = Math.max(0, ...series.map((row) => row.rps));
const peakFailedRate = Math.max(0, ...series.map((row) => row.failedRate));

const html = `<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Self Quote Breakpoint Report</title>
  <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
  <style>
    body { font-family: Arial, sans-serif; margin: 32px; color: #172033; background: #f7f9fc; }
    .summary { display: flex; gap: 12px; margin: 20px 0; flex-wrap: wrap; }
    .metric { background: #fff; border: 1px solid #d9deea; border-radius: 8px; padding: 12px 16px; min-width: 180px; }
    .metric strong { display: block; font-size: 20px; margin-top: 4px; }
    .chart-card { background: #fff; border: 1px solid #d9deea; border-radius: 10px; padding: 16px; }
    .chart-card canvas { width: 100% !important; height: 520px !important; }
    table { background: #fff; border-collapse: collapse; width: 100%; margin-top: 20px; font-size: 14px; }
    th, td { border-bottom: 1px solid #e6e9f0; padding: 8px 10px; text-align: right; }
    th:first-child, td:first-child { text-align: left; }
  </style>
</head>
<body>
  <h1>Self Quote Breakpoint Report</h1>
  <p>${bucketSeconds}초 단위 집계 · Source: ${escapeHtml(inputPath)}</p>
  <div class="summary">
    <div class="metric">Peak p95<strong>${peakP95} ms</strong></div>
    <div class="metric">Peak RPS<strong>${peakRps}</strong></div>
    <div class="metric">Peak failed rate<strong>${peakFailedRate}%</strong></div>
    <div class="metric">Total dropped iterations<strong>${totalDropped}</strong></div>
  </div>
  <section class="chart-card">
    <canvas id="breakpointChart"></canvas>
  </section>
  <table>
    <thead><tr><th>구간</th><th>RPS</th><th>p95 ms</th><th>Median ms</th><th>Dropped</th><th>Failed rate</th></tr></thead>
    <tbody>${series.map((row) => `<tr><td>${row.second}–${row.second + bucketSeconds}s</td><td>${row.rps}</td><td>${row.p95 ?? '-'}</td><td>${row.median ?? '-'}</td><td>${row.dropped}</td><td>${row.failedRate}%</td></tr>`).join('\n')}</tbody>
  </table>
  <script>
    const series = ${JSON.stringify(series)};
    const droppedFailedRateLabels = {
      id: 'droppedFailedRateLabels',
      afterDatasetsDraw(chart) {
        const datasetIndex = chart.data.datasets.findIndex((dataset) => dataset.id === 'dropped');
        if (datasetIndex < 0) return;

        const meta = chart.getDatasetMeta(datasetIndex);
        const ctx = chart.ctx;
        ctx.save();
        ctx.fillStyle = '#7c3aed';
        ctx.font = '12px Arial';
        ctx.textAlign = 'center';
        meta.data.forEach((node, index) => {
          ctx.fillText('(' + series[index].failedRate.toFixed(2) + '%)', node.x, node.y - 10);
        });
        ctx.restore();
      },
    };

    new Chart(document.getElementById('breakpointChart'), {
      type: 'line',
      plugins: [droppedFailedRateLabels],
      data: {
        labels: series.map((row) => row.second + '–' + (row.second + ${bucketSeconds}) + 's'),
        datasets: [
          {
            id: 'dropped',
            type: 'bar',
            label: 'Dropped iterations',
            data: series.map((row) => row.dropped),
            yAxisID: 'yDropped',
            borderColor: 'rgba(124, 58, 237, 0.25)',
            backgroundColor: 'rgba(124, 58, 237, 0.12)',
            borderWidth: 1,
            order: 10,
          },
          {
            label: '실제 RPS',
            data: series.map((row) => row.rps),
            yAxisID: 'yRps',
            borderColor: '#059669',
            backgroundColor: '#059669',
            tension: 0.2,
            order: 1,
          },
          {
            label: 'p95 응답시간',
            data: series.map((row) => row.p95),
            yAxisID: 'yLatency',
            borderColor: '#dc2626',
            backgroundColor: '#dc2626',
            tension: 0.2,
            order: 1,
          },
          {
            label: '중앙값 응답시간',
            data: series.map((row) => row.median),
            yAxisID: 'yLatency',
            borderColor: '#2563eb',
            backgroundColor: '#2563eb',
            tension: 0.2,
            order: 1,
          },
        ],
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        interaction: { mode: 'index', intersect: false },
        plugins: {
          tooltip: {
            callbacks: {
              afterBody(items) {
                const index = items[0].dataIndex;
                return 'Failed rate: ' + series[index].failedRate.toFixed(2) + '%';
              },
            },
          },
        },
        scales: {
          x: { title: { display: true, text: '테스트 경과 시간 (${bucketSeconds}초 구간)' } },
          yLatency: {
            type: 'linear', position: 'left', beginAtZero: true,
            title: { display: true, text: '응답시간 (ms)' },
          },
          yRps: {
            type: 'linear', position: 'right', beginAtZero: true,
            title: { display: true, text: 'RPS' },
            grid: { drawOnChartArea: false },
          },
          yDropped: {
            type: 'linear', position: 'right', beginAtZero: true, offset: true,
            title: { display: true, text: 'Dropped iterations' },
            grid: { drawOnChartArea: false },
          },
        },
      },
    });
  </script>
</body>
</html>`;

fs.mkdirSync(path.dirname(outputPath), { recursive: true });
fs.writeFileSync(outputPath, html);
console.log(`breakpoint report written: ${outputPath}`);

