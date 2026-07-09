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
