// slot-board 공용 조사 헬퍼: 마지막 글자의 받침 유무로 을/를·이/가를 고른다.
// 영문 라벨은 한글 발음 기준으로 판정한다 — RAM(램)은 받침 있음, CPU/GPU/SSD는 받침 없음.
// (전역 표기 규칙: 일괄 '을(를)' 병기 대신 받침 판정으로 자연스러운 조사를 붙인다.)

// 영어 알파벳 이름의 한글 발음이 받침으로 끝나는 글자: L(엘)·M(엠)·N(엔)·R(알).
const CONSONANT_ENDING_LATIN = new Set(['L', 'M', 'N', 'R']);

function hasFinalConsonant(word: string): boolean {
  const lastChar = word.trim().slice(-1);
  if (!lastChar) {
    return false;
  }
  const code = lastChar.charCodeAt(0);
  if (code >= 0xac00 && code <= 0xd7a3) {
    return (code - 0xac00) % 28 !== 0;
  }
  return CONSONANT_ENDING_LATIN.has(lastChar.toUpperCase());
}

/** 목적격 조사 을/를 — 예: 'RAM을', 'GPU를', '메인보드를' */
export function withObjectParticle(word: string): string {
  return `${word}${hasFinalConsonant(word) ? '을' : '를'}`;
}

/** 주격 조사 이/가 — 예: 'RAM이', 'GPU가', '케이스가' */
export function withSubjectParticle(word: string): string {
  return `${word}${hasFinalConsonant(word) ? '이' : '가'}`;
}
