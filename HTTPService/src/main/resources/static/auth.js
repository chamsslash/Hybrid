import { clearAccessToken, getAccessToken, setAccessToken } from "/inmemory.js";
import { getFingerprintData } from "/meta_catcher.js";

function readXsrfCookie() {
    const raw = document.cookie.split('; ').find(row => row.startsWith('XSRF-TOKEN='));
    return raw ? decodeURIComponent(raw.split('=')[1]) : null;
}

async function requestFreshAccessToken() {
    const meta = await getFingerprintData();

    const formData = new URLSearchParams();
    formData.append("FpComponents", JSON.stringify(meta.components));

    const headers = {
        "Content-Type": "application/x-www-form-urlencoded",
        "X-Fingerprint": meta.fingerprint,
        "X-Client-Meta": JSON.stringify(meta.clientMeta),
        "X-SecureUUID": meta.secureUUID,
    };
    // CookieCsrfTokenRepository требует эхо XSRF-cookie в заголовке
    const xsrf = readXsrfCookie();
    if (xsrf) {
        headers["X-XSRF-TOKEN"] = xsrf;
    }

    const resp = await fetch("/exchangeTokens", {
        method: "POST",
        credentials: "same-origin",
        headers,
        body: formData,
    });

    let payload;
    try {
        payload = await resp.json();
    } catch {
        payload = null;
    }

    if (!resp.ok) {
        const msg = payload?.error || payload?.message || `refresh failed: HTTP ${resp.status}`;
        throw new Error(msg);
    }

    const token = payload?.accessToken;
    if (!token) {
        throw new Error("refresh failed: missing accessToken");
    }

    setAccessToken(token);
    return token;
}

// Одно обновление токена на вкладку за раз.
//
// /exchangeTokens — это ротация с детекцией повторного использования. Успешный обмен
// переписывает refreshJti в Redis-сессии (TokensResolver.rotateSession), после чего второй
// запрос с ТОЙ ЖЕ refresh-кукой сервер считает признаком кражи токена. Реакция на это
// (TokensResolver:208) не «отказать», а deleteSessionBySid — сессия уничтожается. Дальше
// хуже: токен, который успел получить победитель гонки, указывает на удалённую сессию, и
// следующее его обновление попадает в ветку TokensResolver:201 с deleteAllSessionsByUser —
// человека разлогинивает на всех устройствах разом.
//
// Клиент попадал под эту проверку сам, без всякой атаки: refreshAccessToken() не схлопывал
// параллельные вызовы, а параллельные 401 здесь обычное дело. hydrateImages выпускает по
// запросу на каждый уникальный objectKey в одном тике (image_loader.js), так что список
// чатов с десятью разными аватарками, пересёкший 15-минутную границу access-токена
// (application.yml: jwt-expiration-ms), давал десять одновременных обменов — из них девять
// сервер обязан был счесть кражей.
//
// Схлопывание закрывает случай одной вкладки. Межвкладочный остаётся: промис живёт в
// памяти вкладки, а refresh-кука общая на домен, поэтому две вкладки на холодном старте
// по-прежнему могут столкнуться. Для него нужна координация между вкладками (Web Locks
// либо BroadcastChannel) — отдельное проектное решение, вынесено в beads dta.
let inFlightRefresh = null;

/**
 * Запускает обмен и запоминает его промис, чтобы параллельные вызывающие получили ТОТ ЖЕ
 * запрос, а не завели каждый свой.
 */
function startRefresh() {
    const attempt = requestFreshAccessToken();
    inFlightRefresh = attempt;

    // Ссылку снимаем и на успехе, и на отказе: неудачный обмен не должен навсегда залипнуть
    // «в полёте», иначе после одного сетевого сбоя вкладка больше никогда не обновит токен.
    // Сверка с attempt — на случай, если этот обмен успели сменить следующим.
    const clear = () => {
        if (inFlightRefresh === attempt) {
            inFlightRefresh = null;
        }
    };
    // .then(clear, clear), а не .finally(): finally отдаёт производный промис, который на
    // отказе attempt тоже отклоняется и, поскольку его никто не слушает, всплывает как
    // unhandled rejection. Оба колбэка здесь не бросают, поэтому производный промис всегда
    // успешен и молчит, а сам attempt уходит вызывающему — отказ обработает он.
    attempt.then(clear, clear);

    return attempt;
}

export async function ensureAccessToken() {
    const existing = getAccessToken();
    if (existing) {
        return existing;
    }
    return inFlightRefresh || startRefresh();
}

/**
 * Обновление после 401.
 *
 * @param {string|null} staleToken токен, с которым запрос получил 401 (для запроса, ушедшего
 *        вовсе без Authorization — null). Отличает «мой токен протух» от «пока мой запрос
 *        летел, токен уже обновил кто-то другой». Во втором случае обмен не нужен вовсе: в
 *        памяти лежит свежий токен, а поход на /exchangeTokens предъявил бы уже
 *        использованную refresh-куку — ровно тот reuse, от которого сервер убивает сессию.
 *        Без этого аргумента одного inFlightRefresh мало: поздние 401 приходят уже ПОСЛЕ
 *        того, как обмен завершился и ссылка снята, и каждый завёл бы новый.
 */
export async function refreshAccessToken(staleToken) {
    const current = getAccessToken();
    // Токен в памяти отличается от того, что провалился, — значит его уже обновили. Сюда же
    // попадает запрос, ушедший без Authorization: ему достаточно появившегося токена.
    if (current && current !== staleToken) {
        return current;
    }
    if (inFlightRefresh) {
        return inFlightRefresh;
    }
    // Чистим только когда действительно идём за новым: безусловный clearAccessToken() в
    // начале затирал бы токен, который только что добыл кто-то другой.
    clearAccessToken();
    return startRefresh();
}

