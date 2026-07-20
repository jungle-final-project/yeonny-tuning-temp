import { getPcAgentConnectionStatus } from './supportApi';

export const PC_AGENT_PROTOCOL_URL = 'buildgraph-pc-agent://open';
export const PC_AGENT_CONNECTION_TIMEOUT_MS = 45_000;
export const PC_AGENT_CONNECTION_FAST_WINDOW_MS = 5_000;
export const PC_AGENT_CONNECTION_FAST_POLL_INTERVAL_MS = 500;
export const PC_AGENT_CONNECTION_SLOW_POLL_INTERVAL_MS = 1_000;

export type PcAgentConnectionPhase = 'approval-required' | 'launching' | 'waiting' | 'connected' | 'timed-out';

export async function ensurePcAgentConnected(
  signal?: AbortSignal,
  onPhaseChanged: (phase: PcAgentConnectionPhase) => void = () => undefined
) {
  if ((await getPcAgentConnectionStatus(signal)).connected) {
    onPhaseChanged('connected');
    return true;
  }

  onPhaseChanged('approval-required');
  launchInstalledPcAgent();
  let elapsedMs = 0;
  while (elapsedMs < PC_AGENT_CONNECTION_TIMEOUT_MS) {
    const intervalMs = elapsedMs < PC_AGENT_CONNECTION_FAST_WINDOW_MS
      ? PC_AGENT_CONNECTION_FAST_POLL_INTERVAL_MS
      : PC_AGENT_CONNECTION_SLOW_POLL_INTERVAL_MS;
    const remainingMs = PC_AGENT_CONNECTION_TIMEOUT_MS - elapsedMs;
    const waitMs = Math.min(intervalMs, remainingMs);
    await delay(waitMs, signal);
    elapsedMs += waitMs;

    if ((await getPcAgentConnectionStatus(signal)).connected) {
      onPhaseChanged('connected');
      return true;
    }
    onPhaseChanged(elapsedMs < PC_AGENT_CONNECTION_FAST_WINDOW_MS ? 'launching' : 'waiting');
  }

  onPhaseChanged('timed-out');
  return false;
}

export function launchInstalledPcAgent() {
  const link = document.createElement('a');
  link.href = PC_AGENT_PROTOCOL_URL;
  link.hidden = true;
  document.body.appendChild(link);
  link.click();
  link.remove();
}

function delay(milliseconds: number, signal?: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    if (signal?.aborted) {
      reject(signal.reason);
      return;
    }
    const onAbort = () => {
      window.clearTimeout(timer);
      reject(signal?.reason);
    };
    const timer = window.setTimeout(() => {
      signal?.removeEventListener('abort', onAbort);
      resolve();
    }, milliseconds);
    signal?.addEventListener('abort', onAbort, { once: true });
  });
}
