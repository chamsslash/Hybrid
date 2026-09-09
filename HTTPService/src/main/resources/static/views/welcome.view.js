// Вью экрана входа (welcome). Рисует форму сама в #app и обрабатывает
// Google-логин и обычный логин. STOMP здесь не используется — unmount
// снимает только DOM-listeners (AbortController) и таймер скрытия ошибки.
import { navigate } from "/router.js";
import api from "/axios.js";
import { getFingerprintData } from "/meta_catcher.js";
import { setAccessToken } from "/inmemory.js";

// `policy` — глобальная Trusted Types policy из trusted_policy.js (обычный
// <script>, подключён в app.html до app.js); как в chatlist.js/index.js.

const WELCOME_HTML = `
<div class="login-container">
    <div class="wordmark">TEBEGRAM</div>
    <h1>С возвращением</h1>
    <p class="subtitle">Войдите, чтобы продолжить переписку</p>

    <a href="#" id="google-login-btn" class="btn btn-google">Войти через Google</a>
    <a href="#" id="register-link" class="btn btn-regular">Eще нет аккаунта?</a>

    <div id="response-div" class="response-message" style="opacity: 0;"></div>

    <form id="regular-login-form">
        <input type="text" name="username" class="input-field" placeholder="Имя" required>
        <input type="password" name="password" class="input-field" placeholder="Пароль" required>
        <button type="submit" class="btn btn-regular">Войти</button>
    </form>
</div>
`;

let ac = null;
let hideTimeout = null;

function readCsrfToken() {
    const raw = document.cookie.split('; ').find(row => row.startsWith('XSRF-TOKEN='));
    return raw ? raw.split('=')[1] : null;
}

function googleHeaders(meta) {
    return {
        'X-Fingerprint': meta.fingerprint,
        'X-SecureUUID': meta.secureUUID,
        'X-CSRF-TOKEN': readCsrfToken(),
        'X-Client-Meta': JSON.stringify(meta.clientMeta)
    };
}

function loginHeaders(meta) {
    return {
        'X-Fingerprint': meta.fingerprint,
        'X-SecureUUID': meta.secureUUID,
        'X-Client-Meta': JSON.stringify(meta.clientMeta),
        'X-CSRF-TOKEN': readCsrfToken()
    };
}

function showError(responseDiv, message) {
    if (!responseDiv) return;
    responseDiv.textContent = message;
    responseDiv.style.opacity = '1';
    if (hideTimeout) clearTimeout(hideTimeout);
    hideTimeout = setTimeout(() => {
        responseDiv.style.opacity = '0';
    }, 5000);
}

export async function mount(params) {
    ac = new AbortController();
    const app = document.getElementById('app');
    app.innerHTML = policy.createHTML(WELCOME_HTML);

    const googleLoginBtn = document.getElementById('google-login-btn');
    const registerLink = document.getElementById('register-link');
    const regularLoginForm = document.getElementById('regular-login-form');
    const responseDiv = document.getElementById('response-div');

    if (params && params.error) {
        showError(responseDiv, params.error);
    }

    const meta = await getFingerprintData();

    registerLink?.addEventListener('click', (event) => {
        event.preventDefault();
        if (responseDiv) responseDiv.style.opacity = '0';
        navigate('/registerpage');
    }, { signal: ac.signal });

    googleLoginBtn?.addEventListener('click', async (event) => {
        event.preventDefault();
        googleLoginBtn.disabled = true;
        googleLoginBtn.textContent = 'Подготовка...';
        if (responseDiv) responseDiv.style.opacity = '0';

        const response = await api.post('/startauth',
            new URLSearchParams({ FpComponents: JSON.stringify(meta.components) }),
            { headers: googleHeaders(meta) }
        );

        const data = response.data;
        if (data.redirectUrl) {
            // Уход на accounts.google.com — реальная навигация, не через router.
            window.location.href = data.redirectUrl;
        } else {
            throw new Error('Сервер не вернул URL для редиректа.');
        }
    }, { signal: ac.signal });

    regularLoginForm?.addEventListener('submit', async (event) => {
        event.preventDefault();
        try {
            const body = new URLSearchParams(new FormData(regularLoginForm));
            body.set('FpComponents', JSON.stringify(meta.components));

            const response = await api.post('/reactive/login', body, { headers: loginHeaders(meta) });
            if (response.data.redirectUri) {
                if (response.data.accessToken) {
                    setAccessToken(response.data.accessToken);
                }
                navigate(response.data.redirectUri);
            }
        } catch (error) {
            showError(responseDiv, error.response?.data || error.message);
        }
    }, { signal: ac.signal });
}

export function unmount() {
    if (ac) ac.abort();
    if (hideTimeout) clearTimeout(hideTimeout);
    ac = null;
    hideTimeout = null;
}
