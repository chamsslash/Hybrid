import { clearAccessToken, getAccessToken, setAccessToken } from "/inmemory.js";
import { getFingerprintData } from "/meta_catcher.js";

async function requestFreshAccessToken() {
    const meta = await getFingerprintData();

    const formData = new URLSearchParams();
    formData.append("FpComponents", JSON.stringify(meta.components));

    const resp = await fetch("/exchangeTokens", {
        method: "POST",
        credentials: "same-origin",
        headers: {
            "Content-Type": "application/x-www-form-urlencoded",
            "X-Fingerprint": meta.fingerprint,
            "X-Client-Meta": JSON.stringify(meta.clientMeta),
            "X-SecureUUID": meta.secureUUID,
        },
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

export async function ensureAccessToken() {
    const existing = getAccessToken();
    if (existing) {
        return existing;
    }
    return requestFreshAccessToken();
}

export async function refreshAccessToken() {
    clearAccessToken();
    return requestFreshAccessToken();
}

