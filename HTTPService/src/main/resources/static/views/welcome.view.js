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
<div class="auth-card">
    <!-- Левая панель. Иллюстрации нет намеренно: пятна собираются из градиентов
         в CSS (.auth-art::before/::after), поэтому ни бинарника в репозитории,
         ни лишнего запроса за картинкой. -->
    <div class="auth-art">
        <div class="auth-brand">
            <span class="auth-logo" aria-hidden="true"></span>
            <span class="auth-wordmark">TEBEGRAM</span>
        </div>
        <p class="auth-slogan">Разговоры лучше — связи крепче.</p>
        <p class="auth-footnote">Присоединяйтесь и начинайте общаться.</p>
    </div>

    <div class="auth-pane">
        <div class="auth-tabs">
            <span class="auth-tab active">Вход</span>
            <a href="/registerpage" class="auth-tab" id="register-link">Регистрация</a>
        </div>

        <h1 class="auth-title">С возвращением</h1>
        <p class="auth-subtitle">Войдите в свой аккаунт</p>

        <form id="regular-login-form" class="auth-form">
            <div class="auth-field">
                <span class="auth-field-icon" aria-hidden="true">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
                         stroke-linecap="round" stroke-linejoin="round">
                        <circle cx="12" cy="8" r="3.6"/><path d="M4.6 20a7.4 7.4 0 0 1 14.8 0"/>
                    </svg>
                </span>
                <input type="text" name="username" class="input-field" placeholder="Имя" required
                       autocomplete="username">
            </div>

            <div class="auth-field with-action">
                <span class="auth-field-icon" aria-hidden="true">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
                         stroke-linecap="round" stroke-linejoin="round">
                        <rect x="4" y="10.5" width="16" height="10" rx="2.5"/>
                        <path d="M8 10.5V7.5a4 4 0 0 1 8 0v3"/>
                    </svg>
                </span>
                <input type="password" name="password" class="input-field" placeholder="Пароль" required
                       autocomplete="current-password" id="password-input">
                <button type="button" class="auth-eye" id="toggle-password"
                        aria-label="Показать пароль" aria-pressed="false">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
                         stroke-linecap="round" stroke-linejoin="round">
                        <path d="M2.5 12S6 5.8 12 5.8 21.5 12 21.5 12 18 18.2 12 18.2 2.5 12 2.5 12z"/>
                        <circle cx="12" cy="12" r="3"/>
                    </svg>
                </button>
            </div>

            <button type="submit" class="btn btn-primary">Войти</button>
        </form>

        <!-- Сообщение об ошибке стоит вплотную к форме, к которой относится: в
             конце карточки, под ссылкой регистрации, оно читалось как замечание
             к ней, а не к неудачному входу. -->
        <div id="response-div" class="response-message" style="opacity: 0;"></div>

        <div class="auth-sep">или</div>

        <a href="#" id="google-login-btn" class="btn btn-google">
            <svg class="g-mark" viewBox="0 0 48 48" aria-hidden="true">
                <path fill="#4285F4" d="M45.1 24.5c0-1.6-.1-3.2-.4-4.7H24v8.9h11.8c-.5 2.7-2 5-4.4 6.6v5.5h7.1c4.1-3.8 6.6-9.4 6.6-16.3z"/>
                <path fill="#34A853" d="M24 46c5.9 0 10.9-2 14.5-5.3l-7.1-5.5c-2 1.3-4.5 2.1-7.4 2.1-5.7 0-10.5-3.8-12.2-9H4.5v5.7C8.1 41.2 15.4 46 24 46z"/>
                <path fill="#FBBC05" d="M11.8 28.3c-.4-1.3-.7-2.7-.7-4.3s.3-3 .7-4.3v-5.7H4.5C2.9 17.1 2 20.4 2 24s.9 6.9 2.5 10l7.3-5.7z"/>
                <path fill="#EA4335" d="M24 10.8c3.2 0 6.1 1.1 8.4 3.3l6.3-6.3C34.9 4.2 29.9 2 24 2 15.4 2 8.1 6.8 4.5 14l7.3 5.7c1.7-5.2 6.5-8.9 12.2-8.9z"/>
            </svg>
            Войти через Google
        </a>

        <p class="auth-switch">Нет аккаунта? <a href="/registerpage" id="register-link-inline">Зарегистрироваться</a></p>

    </div>
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

    // Ссылок на регистрацию две — таб сверху и строка внизу. Обе ведут на один
    // маршрут; href у них настоящий, чтобы работали средняя кнопка мыши и
    // «открыть в новой вкладке», а обычный клик перехватывается роутером.
    for (const link of [registerLink, document.getElementById('register-link-inline')]) {
        link?.addEventListener('click', (event) => {
            event.preventDefault();
            if (responseDiv) responseDiv.style.opacity = '0';
            navigate('/registerpage');
        }, { signal: ac.signal });
    }

    // Показать/скрыть пароль — чистый клиент, никакого запроса.
    const passwordInput = document.getElementById('password-input');
    const toggle = document.getElementById('toggle-password');
    toggle?.addEventListener('click', () => {
        const shown = passwordInput.type === 'text';
        passwordInput.type = shown ? 'password' : 'text';
        toggle.setAttribute('aria-pressed', String(!shown));
        toggle.setAttribute('aria-label', shown ? 'Показать пароль' : 'Скрыть пароль');
    }, { signal: ac.signal });

    googleLoginBtn?.addEventListener('click', async (event) => {
        event.preventDefault();
        googleLoginBtn.disabled = true;
        // Не textContent: внутри кнопки лежит ещё и <svg> с логотипом Google,
        // и присваивание текста снесло бы его вместе с надписью.
        googleLoginBtn.lastChild.textContent = ' Подготовка... ';
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
