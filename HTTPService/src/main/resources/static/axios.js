import { getAccessToken } from "/inmemory.js";
import { refreshAccessToken } from "/auth.js";

function shouldAttachAuth(config) {
    const url = config?.url;
    if (!url || typeof url !== "string") {
        return false;
    }
    // Only attach for same-origin relative URLs.
    if (!url.startsWith("/")) {
        return false;
    }
    // Never attach to refresh/login endpoints.
    return !(
        url.startsWith("/exchangeTokens") ||
        url.startsWith("/reactive/login") ||
        url.startsWith("/reactive/register") ||
        url.startsWith("/verifylogin") ||
        url.startsWith("/startauth")
    );
}

/**
 * Достаёт токен, с которым запрос реально ушёл на провод. Берём из заголовков упавшего
 * конфига, а не из getAccessToken(): к моменту обработки 401 в памяти может лежать уже
 * другой токен, и именно эта разница говорит refreshAccessToken, что обновляться не надо.
 *
 * headers у axios 1.x — AxiosHeaders с методом get(), но интерцептор запроса выше ставит
 * заголовок обычным присваиванием по ключу, поэтому поддерживаем оба доступа.
 */
function readBearer(headers) {
    if (!headers) {
        return null;
    }
    const raw = typeof headers.get === "function"
        ? headers.get("Authorization")
        : headers["Authorization"];
    return typeof raw === "string" && raw.startsWith("Bearer ")
        ? raw.slice("Bearer ".length)
        : null;
}

const api = axios.create({ baseURL: "" });
api.defaults.withCredentials = true;

api.interceptors.request.use(async (config) => {
    if (!config.headers) {
        config.headers = {};
    }

    if (shouldAttachAuth(config) && !config.headers["Authorization"]) {
        const token = getAccessToken();
        if (token) {
            config.headers["Authorization"] = `Bearer ${token}`;
        }
    }

    return config;
});

api.interceptors.response.use(
    (response) => response,
    async (error) => {
        const status = error?.response?.status;
        const original = error?.config;
        if (!original || original._authRetry) {
            return Promise.reject(error);
        }

        // Only retry once, and only for requests that should carry Authorization.
        if (status === 401 && shouldAttachAuth(original)) {
            original._authRetry = true;
            // Токен снимаем ДО refresh: обновление перезапишет то, что лежит в памяти, и
            // отличить «протух мой» от «его уже обновили» станет нечем.
            const staleToken = readBearer(original.headers);
            try {
                const newToken = await refreshAccessToken(staleToken);
                original.headers = original.headers || {};
                original.headers["Authorization"] = `Bearer ${newToken}`;
                return api(original);
            } catch (refreshErr) {
                // Leave decision to the caller, but best-effort redirect keeps UX consistent.
                // Здесь намеренно ЖЁСТКАЯ навигация, а не router.navigate: refresh уже не удался,
                // сессии нет, и полная перезагрузка гарантирует чистое состояние (память, STOMP,
                // подписки вью). Мягкий переход роутером оставил бы висеть подписки и таймеры
                // вью, с которой прилетел этот 401.
                try {
                    window.location.href = "/welcome";
                } catch {
                    // ignore
                }
                return Promise.reject(refreshErr);
            }
        }

        return Promise.reject(error);
    }
);

export default api;
