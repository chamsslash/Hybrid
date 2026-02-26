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
            try {
                const newToken = await refreshAccessToken();
                original.headers = original.headers || {};
                original.headers["Authorization"] = `Bearer ${newToken}`;
                return api(original);
            } catch (refreshErr) {
                // Leave decision to the caller, but best-effort redirect keeps UX consistent.
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
