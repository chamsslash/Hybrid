/**
 * auth.js
 * Модуль для управления Access Token (JWT) в памяти приложения.
 * Он использует замыкание для создания "приватной" переменной,
 * доступ к которой возможен только через экспортируемые функции.
 */

// --- "Приватное" хранилище токена ---
// Эта переменная недоступна извне модуля.
// Она будет хранить наш JWT, пока открыта вкладка браузера.
let inMemoryAccessToken = null;


/**
 * Сохраняет новый Access Token в память.
 * Эта функция должна вызываться после успешного логина или обновления токена.
 * @param {string} token - Новый JWT (Access Token).
 */
export function setAccessToken(token) {
    if (token) {
        console.log("Access Token сохранен в памяти.");
        inMemoryAccessToken = token;
    } else {
        console.warn("Попытка сохранить пустой токен.");
    }
}

/**
 * Возвращает текущий Access Token из памяти.
 * @returns {string | null} - Текущий JWT или null, если его нет.
 */
export function getAccessToken() {
    return inMemoryAccessToken;
}

/**
 * Очищает Access Token из памяти.
 * Эта функция должна вызываться при выходе из системы (logout).
 */
export function clearAccessToken() {
    console.log("Access Token удален из памяти.");
    inMemoryAccessToken = null;
}

/**
 * Проверяет, аутентифицирован ли пользователь в данный момент.
 * (т.е. есть ли у нас в памяти какой-либо токен)
 * @returns {boolean}
 */
export function isAuthenticated() {
    return inMemoryAccessToken !== null;
}/**
 * auth.js
 * Модуль для управления Access Token (JWT) в памяти приложения.
 * Он использует замыкание для создания "приватной" переменной,
 * доступ к которой возможен только через экспортируемые функции.
 */

