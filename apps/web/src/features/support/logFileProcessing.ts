export const SUPPORT_LOG_MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
const RECENT_LOG_WINDOW_MINUTES = 30;
const RECENT_LOG_WINDOW_MS = RECENT_LOG_WINDOW_MINUTES * 60_000;

type TimestampedLogLine = {
  timestampMs: number;
  line: string;
};

type JsonRecord = Record<string, unknown>;

export type PreparedSupportLogFile = {
  file: File;
  notice: string;
};

export class SupportLogFileError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'SupportLogFileError';
  }
}

export async function prepareSupportLogFile(file: File): Promise<PreparedSupportLogFile> {
  assertSupportedLogExtension(file);

  if (shouldExtractRecentWindow(file)) {
    return extractRecentWindow(file);
  }

  return {
    file,
    notice: ''
  };
}

function shouldExtractRecentWindow(file: File) {
  const lowerName = file.name.toLowerCase();
  return lowerName === 'agent-metrics.jsonl' || file.size > SUPPORT_LOG_MAX_UPLOAD_BYTES;
}

function assertSupportedLogExtension(file: File) {
  const lowerName = file.name.toLowerCase();
  if (!lowerName.endsWith('.jsonl') && !lowerName.endsWith('.ndjson')) {
    throw new SupportLogFileError('로그 파일 확장자는 .jsonl 또는 .ndjson만 사용할 수 있습니다.');
  }
}

async function extractRecentWindow(file: File): Promise<PreparedSupportLogFile> {
  let latestTimestampMs = Number.NEGATIVE_INFINITY;
  let retainedLines: TimestampedLogLine[] = [];
  let timestampedLineCount = 0;
  let buffer = '';
  const decoder = new TextDecoder();

  const processLine = (rawLine: string) => {
    const line = rawLine.trim();
    if (!line) return;
    const timestampMs = timestampFromJsonLine(line);
    if (timestampMs == null) return;

    timestampedLineCount += 1;
    if (timestampMs > latestTimestampMs) {
      latestTimestampMs = timestampMs;
    }
    retainedLines.push({ timestampMs, line });
    retainedLines = retainedLines.filter((item) => item.timestampMs >= latestTimestampMs - RECENT_LOG_WINDOW_MS);
  };

  if (typeof file.stream === 'function') {
    const reader = file.stream().getReader();
    try {
      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const parts = buffer.split('\n');
        buffer = parts.pop() ?? '';
        parts.forEach((part) => processLine(part.replace(/\r$/, '')));
      }
    } finally {
      reader.releaseLock();
    }
    buffer += decoder.decode();
  } else {
    buffer = await file.text();
  }

  if (buffer) {
    processLine(buffer.replace(/\r$/, ''));
  }

  if (timestampedLineCount === 0 || !Number.isFinite(latestTimestampMs)) {
    throw new SupportLogFileError(
      '10MiB를 넘는 누적 로그는 timestamp가 있는 JSONL 라인에서 최근 30분만 추출해야 합니다. PCAgent 로그 파일인지 확인해 주세요.'
    );
  }

  const cutoffMs = latestTimestampMs - RECENT_LOG_WINDOW_MS;
  retainedLines = retainedLines.filter((item) => item.timestampMs >= cutoffMs);
  if (retainedLines.length === 0) {
    throw new SupportLogFileError('최근 30분 범위에 포함되는 로그 라인을 찾지 못했습니다.');
  }

  const body = `${retainedLines.map((item) => item.line).join('\n')}\n`;
  const extractedFile = new File([body], recentWindowFileName(file.name), { type: 'application/x-ndjson' });
  if (extractedFile.size > SUPPORT_LOG_MAX_UPLOAD_BYTES) {
    throw new SupportLogFileError(
      '최근 30분 로그만 추출해도 10MiB를 넘습니다. PCAgent의 AS 접수 신청 버튼으로 선택 구간 업로드를 진행해 주세요.'
    );
  }

  return {
    file: extractedFile,
    notice: `원본 로그에서 최신 기록 기준 최근 30분 ${retainedLines.length.toLocaleString()}개 라인만 추출했습니다. (${formatLogTimestamp(cutoffMs)} ~ ${formatLogTimestamp(latestTimestampMs)})`
  };
}

function timestampFromJsonLine(line: string) {
  try {
    const value = JSON.parse(line) as unknown;
    if (!isJsonRecord(value)) return null;
    return firstTimestampMs([
      value.timestamp,
      value.collectedAt,
      value.detectedAt,
      value.receivedAt,
      value.createdAt,
      isJsonRecord(value.payload) ? value.payload.timestamp : null,
      isJsonRecord(value.payload) ? value.payload.collectedAt : null,
      isJsonRecord(value.payload) ? value.payload.detectedAt : null
    ]);
  } catch {
    return null;
  }
}

function firstTimestampMs(values: unknown[]) {
  for (const value of values) {
    const timestampMs = toTimestampMs(value);
    if (timestampMs != null) return timestampMs;
  }
  return null;
}

function toTimestampMs(value: unknown) {
  if (typeof value === 'string' && value.trim()) {
    const timestampMs = Date.parse(value);
    return Number.isNaN(timestampMs) ? null : timestampMs;
  }
  if (typeof value === 'number' && Number.isFinite(value) && value > 0) {
    return value > 10_000_000_000 ? value : value * 1000;
  }
  return null;
}

function isJsonRecord(value: unknown): value is JsonRecord {
  return typeof value === 'object' && value != null && !Array.isArray(value);
}

function recentWindowFileName(fileName: string) {
  const cleanName = fileName.trim() || 'agent-metrics.jsonl';
  const extensionIndex = cleanName.lastIndexOf('.');
  const baseName = extensionIndex > 0 ? cleanName.slice(0, extensionIndex) : cleanName;
  return `${baseName}-recent-30m.jsonl`;
}

function formatLogTimestamp(timestampMs: number) {
  return new Date(timestampMs).toLocaleString('ko-KR', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  });
}
