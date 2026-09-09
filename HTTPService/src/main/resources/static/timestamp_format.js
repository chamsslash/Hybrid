// Общий форматтер серверного ISO-8601 timestamp'а сообщений/чатов.
// Используется в chat.view.js и chatlist.view.js (beads 55/57/58).
export function formatMessageTimestamp(isoTimestamp) {
    if (!isoTimestamp) return '';
    const date = new Date(isoTimestamp);
    if (Number.isNaN(date.getTime())) return isoTimestamp;
    return date.toLocaleString('ru-RU', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' });
}
