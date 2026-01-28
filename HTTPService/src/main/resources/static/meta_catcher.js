// fingerprint.js

const FP_CACHE_KEY = "fp_cache";
const FP_TTL_MS = 24 * 60 * 60 * 1000;

function loadFpCache() {
    const raw = localStorage.getItem(FP_CACHE_KEY);
    if (!raw) {
        return null;
    }
    try {
        const data = JSON.parse(raw);
        if (!data || !data.ts || (Date.now() - data.ts) > FP_TTL_MS) {
            return null;
        }
        return data;
    } catch {
        return null;
    }
}

function saveFpCache(data) {
    localStorage.setItem(FP_CACHE_KEY, JSON.stringify({ ...data, ts: Date.now() }));
}

/**
 * Генерирует и сохраняет уникальный идентификатор пользователя (UUID),
 * получает цифровой отпечаток браузера и метаданные клиента.
 *
 * @returns {Promise<object>} Объект, содержащий secureUUID, بصمة الإصبع и clientMeta.
 */
export async function getFingerprintData() {
    const cached = loadFpCache();
    if (cached) {
        return cached;
    }

    let secureUUID = localStorage.getItem("secureUUID");
    if (!secureUUID) {
        secureUUID = crypto.randomUUID();
        localStorage.setItem("secureUUID", secureUUID);
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

    let clientMeta = {
        ip: "0.0.0.0",
        country: "unknown",
        city: "unknown",
        asn: "unknown",
        org: "unknown"
    };
    try {
        const resp = await fetch('https://ip-api.com/json/');
        if (resp.ok) {
            const metadata = await resp.json();
            clientMeta = {
                ip: metadata?.query ?? clientMeta.ip,
                country: metadata?.country ?? clientMeta.country,
                city: metadata?.city ?? clientMeta.city,
                asn: metadata?.as ?? clientMeta.asn,
                org: metadata?.org ?? clientMeta.org
            };
        } else {
            console.warn("ip-api ответил статусом", resp.status, "— оставляем значения по умолчанию");
        }
    } catch (error) {
        console.warn("Не удалось получить clientMeta, продолжаем без неё:", error);
    }

    const payload = {
        secureUUID,
        fingerprint,
        components,
        clientMeta
    };
    saveFpCache(payload);
    return payload;
}
