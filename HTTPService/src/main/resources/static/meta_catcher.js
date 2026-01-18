// fingerprint.js

export async function getFingerprintData() {
    let secureUUID = localStorage.getItem("secureUUID");
    if (!secureUUID) {
        secureUUID = crypto.randomUUID();
        localStorage.setItem("secureUUID", secureUUID);
    }

    // Базовый набор характеристик на случай, если FingerprintJS или внешние сервисы недоступны.
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
        components = (result.components && result.components !== "undefined") ? result.components : fallbackComponents;
    } catch (error) {
        console.warn("FingerprintJS недоступен, используем запасные данные:", error);
    }

    // Значения по умолчанию, чтобы серверная логика не падала на null.
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

    return {
        secureUUID,
        fingerprint,
        components,
        clientMeta
    };
}
