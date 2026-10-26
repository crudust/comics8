/**
 * Display formatting shared by the Library and Settings views.
 *
 * Relative-time wording mirrors `core/src/main/kotlin/i18n/I18nExtensions.kt::formatRelativeTime`
 * (분/시간/일 전), with "어제" added for the single-day case the web UI shows.
 */

/** Absolute `yyyy-MM-dd HH:mm:ss` timestamp matching Kotlin SimpleDateFormat in SyncBackupSettingsPane.kt. */
export function formatAbsoluteTime(timestamp: number): string {
  if (!timestamp || timestamp <= 0) return '';
  const d = new Date(timestamp);
  const pad = (n: number) => `${n}`.padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

/** Absolute `yyyy.MM.dd` label used once an item is older than a week. */
export function formatDate(timestamp: number): string {
  const date = new Date(timestamp);
  const year = date.getFullYear();
  const month = `${date.getMonth() + 1}`.padStart(2, '0');
  const day = `${date.getDate()}`.padStart(2, '0');
  return `${year}.${month}.${day}`;
}

/** `방금 전` / `5분 전` / `2시간 전` / `어제` / `3일 전` / `2026.10.01`. */
export function formatRelativeTime(timestamp: number, now = Date.now()): string {
  if (!timestamp || timestamp <= 0) return '';
  const minutes = Math.floor(Math.max(0, now - timestamp) / 60_000);
  if (minutes < 1) return '방금 전';
  if (minutes < 60) return `${minutes}분 전`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}시간 전`;
  const days = Math.floor(hours / 24);
  if (days === 1) return '어제';
  if (days < 7) return `${days}일 전`;
  return formatDate(timestamp);
}

/** `mm:ss`, used by the pairing-code countdown. */
export function formatCountdown(totalSeconds: number): string {
  const safe = Math.max(0, Math.trunc(totalSeconds));
  const minutes = Math.floor(safe / 60);
  const seconds = safe % 60;
  return `${`${minutes}`.padStart(2, '0')}:${`${seconds}`.padStart(2, '0')}`;
}

/** "24 / 50 페이지 (48%)" — `pageIndex` is 0-based, the label is 1-based. */
export function formatPageProgress(
  pageIndex: number,
  totalPages: number,
): { current: number; total: number; percent: number; label: string } {
  const total = Math.max(0, Math.trunc(totalPages));
  const current = Math.max(0, Math.trunc(pageIndex)) + 1;
  if (total === 0) {
    return { current, total: 0, percent: 0, label: `${current} 페이지` };
  }
  const bounded = Math.min(current, total);
  const percent = Math.round((bounded / total) * 100);
  return { current: bounded, total, percent, label: `${bounded} / ${total} 페이지 (${percent}%)` };
}
