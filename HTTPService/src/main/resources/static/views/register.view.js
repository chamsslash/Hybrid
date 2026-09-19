// Вью экрана регистрации. Рисует форму сама в #app. CSRF-заголовок читается
// из cookie XSRF-TOKEN (клиентская форма не содержит серверного <input
// name="_csrf"> — в shell его никто не кладёт в модель).
import { navigate } from "/router.js";
import api from "/axios.js";
import { getFingerprintData } from "/meta_catcher.js";
import { setAccessToken } from "/inmemory.js";

// `policy` — глобальная Trusted Types policy из trusted_policy.js (как в welcome.view.js).

const REGISTER_HTML = `
<div class="auth-card">
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
            <a href="/welcome" class="auth-tab" id="back-btn">Вход</a>
            <span class="auth-tab active">Регистрация</span>
        </div>

        <h1 class="auth-title">Создать аккаунт</h1>
        <p class="auth-subtitle">Придумайте ник и пароль</p>

        <form id="register-form" class="auth-form">
            <div class="auth-field">
                <span class="auth-field-icon" aria-hidden="true">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
                         stroke-linecap="round" stroke-linejoin="round">
                        <circle cx="12" cy="8" r="3.6"/><path d="M4.6 20a7.4 7.4 0 0 1 14.8 0"/>
                    </svg>
                </span>
                <input type="text" name="username" class="input-field" placeholder="Придумайте логин"
                       required autocomplete="username">
            </div>

            <div class="auth-field with-action">
                <span class="auth-field-icon" aria-hidden="true">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
                         stroke-linecap="round" stroke-linejoin="round">
                        <rect x="4" y="10.5" width="16" height="10" rx="2.5"/>
                        <path d="M8 10.5V7.5a4 4 0 0 1 8 0v3"/>
                    </svg>
                </span>
                <input type="password" name="password" class="input-field" placeholder="Придумайте пароль"
                       required autocomplete="new-password" id="password-input">
                <button type="button" class="auth-eye" id="toggle-password"
                        aria-label="Показать пароль" aria-pressed="false">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
                         stroke-linecap="round" stroke-linejoin="round">
                        <path d="M2.5 12S6 5.8 12 5.8 21.5 12 21.5 12 18 18.2 12 18.2 2.5 12 2.5 12z"/>
                        <circle cx="12" cy="12" r="3"/>
                    </svg>
                </button>
            </div>

            <label class="auth-file">
                <input type="file" name="userimage" accept="image/*" id="avatar-file">
                <span class="auth-file-icon" aria-hidden="true">
                    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor"
                         stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                        <rect x="3" y="6" width="18" height="14" rx="2.5"/>
                        <circle cx="12" cy="13" r="3.2"/><path d="M8.5 6l1.4-2h4.2l1.4 2"/>
                    </svg>
                </span>
                <span>Аватарка — <span class="auth-file-name" id="avatar-file-name">не выбрана</span></span>
            </label>

            <button type="submit" class="btn btn-primary">Создать аккаунт</button>
        </form>

        <!-- См. комментарий в welcome.view.js: ошибка живёт рядом с формой. -->
        <div id="register-response" class="response-message" style="opacity: 0;"></div>

        <p class="auth-switch">Уже есть аккаунт? <a href="/welcome" id="login-link-inline">Войти</a></p>

    </div>
</div>
`;

let ac = null;

function readCsrfToken() {
    const raw = document.cookie.split('; ').find(row => row.startsWith('XSRF-TOKEN='));
    return raw ? raw.split('=')[1] : null;
}

function showError(responseDiv, message) {
    if (!responseDiv) return;
    responseDiv.textContent = message;
    responseDiv.style.opacity = '1';
}

export async function mount() {
    ac = new AbortController();
    const app = document.getElementById('app');
    app.innerHTML = policy.createHTML(REGISTER_HTML);

    const registerForm = document.getElementById('register-form');
    const responseDiv = document.getElementById('register-response');

    // Возврат явным маршрутом, а не history.back(): на /registerpage заходят и по прямой
    // ссылке или закладке, и тогда возвращаться в истории некуда — кнопка либо увела бы с
    // сайта, либо не сделала бы ничего. Комбинировать «назад по истории, если она есть»
    // тоже не стали: предсказуемость важнее — с этого экрана всегда ведёт /welcome.
    // navigate(), а не window.location.href: /welcome — роут того же app-shell'а,
    // полная перезагрузка здесь не нужна.
    // Ссылок на вход две — таб сверху и строка внизу. href настоящий (работают
    // средняя кнопка и «открыть в новой вкладке»), обычный клик перехватывает роутер.
    for (const link of [document.getElementById('back-btn'),
                        document.getElementById('login-link-inline')]) {
        link?.addEventListener('click', (event) => {
            event.preventDefault();
            navigate('/welcome');
        }, { signal: ac.signal });
    }

    const passwordInput = document.getElementById('password-input');
    const toggle = document.getElementById('toggle-password');
    toggle?.addEventListener('click', () => {
        const shown = passwordInput.type === 'text';
        passwordInput.type = shown ? 'password' : 'text';
        toggle.setAttribute('aria-pressed', String(!shown));
        toggle.setAttribute('aria-label', shown ? 'Показать пароль' : 'Скрыть пароль');
    }, { signal: ac.signal });

    // Нативный input[type=file] скрыт (в тёмной теме он выглядит инородно), поэтому
    // имя выбранного файла показываем сами — иначе выбор ничем не подтверждается.
    const fileInput = document.getElementById('avatar-file');
    fileInput?.addEventListener('change', () => {
        const name = fileInput.files?.[0]?.name;
        document.getElementById('avatar-file-name').textContent = name || 'не выбрана';
    }, { signal: ac.signal });

    registerForm?.addEventListener('submit', async (event) => {
        event.preventDefault();

        let meta;
        try {
            meta = await getFingerprintData();
        } catch (error) {
            console.error('Ошибка при получении фингерпринта:', error);
            showError(responseDiv, 'Не удалось получить отпечаток браузера. Пожалуйста, попробуйте снова.');
            return;
        }

        const formData = new FormData(registerForm);
        formData.append('FpComponents', JSON.stringify(meta.components));

        try {
            const response = await api.post('/reactive/register', formData, {
                headers: {
                    'X-Fingerprint': meta.fingerprint,
                    'X-SecureUUID': meta.secureUUID,
                    'X-Client-Meta': JSON.stringify(meta.clientMeta),
                    'X-CSRF-TOKEN': readCsrfToken()
                }
            });

            if (response.data.redirectUri) {
                if (response.data.accessToken) {
                    setAccessToken(response.data.accessToken);
                }
                navigate(response.data.redirectUri);
            }
        } catch (error) {
            console.error('Ошибка при отправке формы:', error);
            showError(responseDiv, 'Ошибка запроса: ' + (error.response?.data || error.message));
        }
    }, { signal: ac.signal });
}

export function unmount() {
    if (ac) ac.abort();
    ac = null;
}
