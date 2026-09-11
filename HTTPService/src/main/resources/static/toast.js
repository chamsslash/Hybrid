// Всплывающие уведомления. Вынесены из chat.view.js, когда тот же механизм понадобился
// экрану профиля (beads ehe) — копия рядом была бы прямым дублированием.
//
// Контейнер #toastContainer живёт в шелле (templates/app.html) СОСЕДОМ #app, а не его
// потомком: смена вью заменяет содержимое #app целиком, и тост, показанный перед уходом
// с экрана, иначе исчез бы вместе с ним.

/**
 * Показывает тост и возвращает его узел (или null, если контейнера нет).
 * duration = 0 означает «висит, пока не уберут вручную» — вызывающий сам зовёт remove().
 */
export function showToast(message, type = 'info', duration = 3000) {
    const container = document.getElementById('toastContainer');
    if (!container) return null;
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    toast.textContent = message;
    container.appendChild(toast);
    if (duration > 0) {
        setTimeout(() => toast.remove(), duration);
    }
    return toast;
}
