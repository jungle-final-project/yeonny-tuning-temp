const seoulDateTimeFormatter = new Intl.DateTimeFormat('sv-SE', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false
});

const seoulTimeFormatter = new Intl.DateTimeFormat('sv-SE', {
  timeZone: 'Asia/Seoul',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false
});

// 관리자 이력 화면 공용: UTC ISO 문자열을 KST 'YYYY-MM-DD HH:mm'으로 표시
const kstDateTimeFormatter = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23'
});

export function formatKstDateTime(value?: string | null) {
  const date = parseDate(value);
  if (!date) {
    return '-';
  }
  const parts = new Map(kstDateTimeFormatter.formatToParts(date).map((part) => [part.type, part.value]));
  return `${parts.get('year')}-${parts.get('month')}-${parts.get('day')} ${parts.get('hour')}:${parts.get('minute')}`;
}

export function formatSeoulDateTime(value?: string | null) {
  const date = parseDate(value);
  return date ? seoulDateTimeFormatter.format(date) : '-';
}

export function formatSeoulTime(value?: string | null) {
  const date = parseDate(value);
  return date ? seoulTimeFormatter.format(date) : '-';
}

function parseDate(value?: string | null) {
  if (!value) {
    return null;
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}
