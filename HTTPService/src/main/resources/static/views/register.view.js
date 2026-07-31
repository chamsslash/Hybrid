// Вью экрана регистрации. Рисует форму сама в #app. CSRF-заголовок читается
// из cookie XSRF-TOKEN (клиентская форма не содержит серверного <input
// name="_csrf"> — в shell его никто не кладёт в модель).
import { navigate } from "/router.js";
import api from "/axios.js";
import { getFingerprintData } from "/meta_catcher.js";
import { setAccessToken } from "/inmemory.js";

// `policy` — глобальная Trusted Types policy из trusted_policy.js (как в welcome.view.js).

const REGISTER_HTML = `
<div class="post-feed" style="max-width: 440px;">
    <div class="list-head">
        <h1 class="title"><small>TEBEGRAM</small>Регистрация</h1>
    </div>

    <form id="register-form" class="add-comment">
        <label>
            Имя пользователя
            <input type="text" name="username" placeholder="Придумайте логин" required>
        </label>

        <label>
            Аватар
            <input type="file" name="userimage" accept="image/*">
        </label>

        <label>
            Пароль
            <input type="password" name="password" placeholder="Придумайте пароль" required>
        </label>

        <button type="submit">Создать аккаунт</button>
    </form>

    <div id="register-response" style="opacity: 0; transition: opacity 1s ease; margin-top: 20px; text-align: center;"></div>
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
