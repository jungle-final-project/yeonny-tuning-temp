import { API_BASE_URL } from '../../lib/api';
import { issueAgentActivationToken } from './supportApi';

type ZipEntryInput = {
  name: string;
  data: Uint8Array<ArrayBuffer>;
};

type AgentDownloadManifest = {
  version: string;
  downloadUrl: string;
  sha256?: string;
};

const crc32Table = createCrc32Table();

export async function downloadPcAgentForCurrentUser() {
  const activation = await issueAgentActivationToken();
  await downloadAgentPackage(activation.activationToken);
}

// AS 접수 흐름에서는 입력한 증상을 zip의 activation 설정에 동봉해 exe가 첫 실행 시 이어받는다.
export async function downloadAgentPackage(activationToken: string, symptom?: string, symptomType?: string) {
  const manifest = await fetchAgentDownloadManifest();
  const exe = await fetchAgentExecutable(manifest);
  const config = {
    apiBaseUrl: resolveAgentApiBaseUrl(),
    webBaseUrl: window.location.origin,
    activationToken,
    ...(symptom ? { symptom } : {}),
    ...(symptomType ? { symptomType } : {}),
    environment: import.meta.env.MODE ?? 'local'
  };
  const encoder = new TextEncoder();
  const zip = createZipBlob([
    { name: 'PCAgent.exe', data: exe },
    { name: 'pcagent-activation.json', data: encoder.encode(`${JSON.stringify(config, null, 2)}\n`) },
    { name: 'README.txt', data: encoder.encode(createAgentPackageReadme()) }
  ]);
  const url = URL.createObjectURL(zip);
  downloadUrl(url, 'PCAgent.zip');
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

async function fetchAgentDownloadManifest(): Promise<AgentDownloadManifest> {
  const response = await fetch('/downloads/pc-agent/latest.json', { cache: 'no-store' });
  if (!response.ok) {
    throw new Error('Agent manifest download failed.');
  }
  const body: unknown = await response.json();
  if (!isAgentDownloadManifest(body)) {
    throw new Error('Agent manifest is invalid.');
  }
  return body;
}

function isAgentDownloadManifest(value: unknown): value is AgentDownloadManifest {
  if (typeof value !== 'object' || value == null) {
    return false;
  }
  const manifest = value as Partial<AgentDownloadManifest>;
  return typeof manifest.version === 'string'
    && typeof manifest.downloadUrl === 'string'
    && (manifest.sha256 == null || typeof manifest.sha256 === 'string');
}

async function fetchAgentExecutable(manifest: AgentDownloadManifest): Promise<Uint8Array<ArrayBuffer>> {
  const manifestUrl = new URL('/downloads/pc-agent/latest.json', window.location.origin);
  const executableUrl = new URL(manifest.downloadUrl, manifestUrl);
  executableUrl.searchParams.set('v', manifest.version);
  const response = await fetch(executableUrl, { cache: 'no-store' });
  if (!response.ok) {
    throw new Error('Agent exe download failed.');
  }
  const exe = new Uint8Array(await response.arrayBuffer());
  if (manifest.sha256 && globalThis.crypto?.subtle) {
    const actualHash = await sha256Hex(exe);
    if (actualHash !== manifest.sha256.toLowerCase()) {
      throw new Error('Agent exe checksum mismatch.');
    }
  }
  return exe;
}

async function sha256Hex(data: Uint8Array<ArrayBuffer>) {
  const digest = await globalThis.crypto.subtle.digest('SHA-256', data);
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('');
}

function createAgentPackageReadme() {
  return [
    'PCAgent',
    '',
    '1. Extract this zip file first.',
    '2. Keep PCAgent.exe and pcagent-activation.json in the same folder.',
    '3. Double-click PCAgent.exe.',
    '',
    'pcagent-activation.json is a one-time registration file.',
    'PCAgent deletes it automatically after registration succeeds.',
    ''
  ].join('\n');
}

function createZipBlob(entries: ZipEntryInput[]) {
  const encoder = new TextEncoder();
  const localParts: Uint8Array<ArrayBuffer>[] = [];
  const centralParts: Uint8Array<ArrayBuffer>[] = [];
  let offset = 0;
  const { dosTime, dosDate } = toDosDateTime(new Date());

  entries.forEach((entry) => {
    const nameBytes = encoder.encode(entry.name.replace(/\\/g, '/'));
    const crc = crc32(entry.data);

    const localHeader = new Uint8Array(30 + nameBytes.length);
    const localView = new DataView(localHeader.buffer);
    localView.setUint32(0, 0x04034b50, true);
    localView.setUint16(4, 20, true);
    localView.setUint16(6, 0, true);
    localView.setUint16(8, 0, true);
    localView.setUint16(10, dosTime, true);
    localView.setUint16(12, dosDate, true);
    localView.setUint32(14, crc, true);
    localView.setUint32(18, entry.data.length, true);
    localView.setUint32(22, entry.data.length, true);
    localView.setUint16(26, nameBytes.length, true);
    localView.setUint16(28, 0, true);
    localHeader.set(nameBytes, 30);

    const centralHeader = new Uint8Array(46 + nameBytes.length);
    const centralView = new DataView(centralHeader.buffer);
    centralView.setUint32(0, 0x02014b50, true);
    centralView.setUint16(4, 20, true);
    centralView.setUint16(6, 20, true);
    centralView.setUint16(8, 0, true);
    centralView.setUint16(10, 0, true);
    centralView.setUint16(12, dosTime, true);
    centralView.setUint16(14, dosDate, true);
    centralView.setUint32(16, crc, true);
    centralView.setUint32(20, entry.data.length, true);
    centralView.setUint32(24, entry.data.length, true);
    centralView.setUint16(28, nameBytes.length, true);
    centralView.setUint16(30, 0, true);
    centralView.setUint16(32, 0, true);
    centralView.setUint16(34, 0, true);
    centralView.setUint16(36, 0, true);
    centralView.setUint32(38, 0, true);
    centralView.setUint32(42, offset, true);
    centralHeader.set(nameBytes, 46);

    localParts.push(localHeader, entry.data);
    centralParts.push(centralHeader);
    offset += localHeader.length + entry.data.length;
  });

  const centralOffset = offset;
  const centralSize = centralParts.reduce((size, part) => size + part.length, 0);
  const endRecord = new Uint8Array(22);
  const endView = new DataView(endRecord.buffer);
  endView.setUint32(0, 0x06054b50, true);
  endView.setUint16(4, 0, true);
  endView.setUint16(6, 0, true);
  endView.setUint16(8, entries.length, true);
  endView.setUint16(10, entries.length, true);
  endView.setUint32(12, centralSize, true);
  endView.setUint32(16, centralOffset, true);
  endView.setUint16(20, 0, true);

  return new Blob([...localParts, ...centralParts, endRecord], { type: 'application/zip' });
}

function createCrc32Table() {
  const table = new Uint32Array(256);
  for (let index = 0; index < table.length; index += 1) {
    let value = index;
    for (let bit = 0; bit < 8; bit += 1) {
      value = value & 1 ? 0xedb88320 ^ (value >>> 1) : value >>> 1;
    }
    table[index] = value >>> 0;
  }
  return table;
}

function crc32(data: Uint8Array) {
  let crc = 0xffffffff;
  data.forEach((byte) => {
    crc = crc32Table[(crc ^ byte) & 0xff] ^ (crc >>> 8);
  });
  return (crc ^ 0xffffffff) >>> 0;
}

function toDosDateTime(date: Date) {
  const year = Math.max(1980, date.getFullYear());
  const dosTime = (date.getHours() << 11) | (date.getMinutes() << 5) | Math.floor(date.getSeconds() / 2);
  const dosDate = ((year - 1980) << 9) | ((date.getMonth() + 1) << 5) | date.getDate();
  return { dosTime, dosDate };
}

function resolveAgentApiBaseUrl() {
  const configured = API_BASE_URL.trim().replace(/\/$/, '');
  if (/^https?:\/\//.test(configured)) {
    return configured;
  }
  if (configured.startsWith('/')) {
    return `${window.location.origin}${configured}`;
  }
  if (isLocalDevWebOrigin()) {
    return `${window.location.protocol}//${window.location.hostname}:8080`;
  }
  return window.location.origin;
}

function isLocalDevWebOrigin() {
  return ['localhost', '127.0.0.1'].includes(window.location.hostname) && window.location.port === '5173';
}

function downloadUrl(url: string, filename: string) {
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
}
