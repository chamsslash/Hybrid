// fingerprint.js

/**
 * Генерирует и сохраняет уникальный идентификатор пользователя (UUID),
 * получает цифровой отпечаток браузера и метаданные клиента.
 *
 * @returns {Promise<object>} Объект, содержащий secureUUID, بصمة الإصبع и clientMeta.
 */
export async function getFingerprintData() {
    try {
        let secureUUID = localStorage.getItem("secureUUID");
        if (!secureUUID) {
            secureUUID = crypto.randomUUID();
            localStorage.setItem("secureUUID", secureUUID);
        }

        const fp = await FingerprintJS.load();
        const result = await fp.get();
        const fingerprint = result.visitorId;
        let components = result.components;

        if (typeof components === "undefined" || !components || components==="undefined") {
            components = {
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
        }

        const metadata = await fetch('http://ip-api.com/json/').then(res => res.json());
        const clientMeta = {
            ip: metadata.query,
            country: metadata.country,
            city: metadata.city,
            asn: metadata.as,
            org: metadata.org
        };

        return {
            secureUUID,
            fingerprint,
            components,
            clientMeta
        };

    } catch (error) {
        console.error("Ошибка при получении данных отпечатка:", error);
        // Возвращаем null или обрабатываем ошибку соответствующим образом
        return null;
    }
}