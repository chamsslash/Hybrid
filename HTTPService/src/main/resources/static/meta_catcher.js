// fingerprint.js

const FP_CACHE_KEY = "fp_cache";
const FP_TTL_MS = 24 * 60 * 60 * 1000;

function loadFpCache() {
    try {
        const raw = localStorage.getItem(FP_CACHE_KEY);
        if (!raw) {
            return null;
        }
        const data = JSON.parse(raw);
        if (!data || !data.ts || (Date.now() - data.ts) > FP_TTL_MS) {
            return null;
        }
        return data;
    } catch {
        // localStorage может быть недоступен (приватный режим, блокировка расширением) —
        // просто не кэшируем.
        return null;
    }
}

function saveFpCache(data) {
    try {
        localStorage.setItem(FP_CACHE_KEY, JSON.stringify({ ...data, ts: Date.now() }));
    } catch {
        // см. loadFpCache — кэш best-effort, не критичен для работы
    }
}

/**
 * crypto.randomUUID() требует secure context (HTTPS либо буквально localhost/127.0.0.1) —
 * недоступен на http://<кастомный-хост> (см. beads h13). crypto.getRandomValues() такого
 * ограничения не имеет, поэтому используем её как fallback для сборки UUID v4.
 */
function generateUUID() {
    if (typeof crypto.randomUUID === "function") {
        return crypto.randomUUID();
    }
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    const hex = [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/**
 * Генерирует и сохраняет уникальный идентификатор пользователя (UUID),
 * получает цифровой отпечаток браузера и метаданные клиента.
 *
 * @returns {Promise<object>} Объект, содержащий secureUUID, отпечаток и clientMeta.
 */
export async function getFingerprintData() {
    const cached = loadFpCache();
    if (cached) {
        return cached;
    }

    let secureUUID;
    try {
        secureUUID = localStorage.getItem("secureUUID");
    } catch {
        secureUUID = null;
    }
    if (!secureUUID) {
        secureUUID = generateUUID();
        try {
            localStorage.setItem("secureUUID", secureUUID);
        } catch {
            // storage недоступен — UUID всё равно рабочий для этой сессии, просто не переживёт reload
        }
    }

    const fallbackComponents = {
        userAgent: navigator.userAgent,
        language: navigator.language,
        platform: navigator.platform,
        timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
        screenResolution: `${screen.width}x${screen.height}`,
        colorDepth: screen.colorDepth,
        deviceMemory: navigator.deviceMemory || "unknown",
        hardwareConcurrency: navigator.hardwareConcurrency || "unknown",
        touchSupport: 'ontouchstart' in window || navigator.maxTouchPoints > 0,
    };

    let fingerprint = "unknown";
    let components = fallbackComponents;
    try {
        const fp = await FingerprintJS.load();
        const result = await fp.get();
        fingerprint = result.visitorId || fingerprint;
        if (result.components && result.components !== "undefined") {
            components = result.components;
        }
    } catch (error) {
        console.warn("FingerprintJS недоступен, используем запасные данные:", error);
    }

    // clientMeta заполняется значениями по умолчанию намеренно.
    //
    // Здесь стоял ожидающий fetch('https://ip-api.com/json/') прямо в критическом пути входа:
    // его результата ждал /exchangeTokens, то есть и первый вход, и каждое обновление токена с
    // протухшим кэшем. Ответа он не приносил никогда — бесплатный ip-api HTTPS не отдаёт
    // (проверено с чистой сети сервера: https -> 403, http -> 200), поэтому resp.ok всегда
    // ложь и clientMeta всегда оставалась ровно тем, чем инициализирована ниже.
    //
    // Починить «правильно», сходив по http, нельзя: страница отдаётся по https, и браузер
    // заблокирует такой запрос как mixed content. Взять данные с бэкенда тоже нечем — сервер
    // видит адрес прокси, а не клиента. То есть выбор был между «ждать впустую» и «не ждать»;
    // ожидание убрано, значения остались те же самые.
    //
    // Для сервера это ничего не меняет: FpSimilarityScore.computeLikelihood сравнивает новую
    // meta со СТАРОЙ, сохранённой при выдаче сессии, а обе состоят из этих же дефолтов —
    // сравнение как было тождественным, так и осталось. PTR по ip=0.0.0.0 на бэкенде
    // (ReverseDnsResolver в MVC_Service) тоже отрабатывает ровно как раньше.
    const clientMeta = {
        ip: "0.0.0.0",
        country: "unknown",
        city: "unknown",
        asn: "unknown",
        org: "unknown"
    };

    const payload = {
        secureUUID,
        fingerprint,
        components,
        clientMeta
    };
    saveFpCache(payload);
    return payload;
}
