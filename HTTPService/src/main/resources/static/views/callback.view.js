// Вью OAuth-callback (перенесена из static/callback.js + templates/callback.html под
// контракт mount/unmount SPA-роутера, beads j35).
//
// Ради этого переезда всё и делалось: callback был отдельным Thymeleaf-документом вне
// шелла, поэтому уход на redirectUri был сменой документа, и accessToken, положенный
// строкой выше в память (inmemory.js на то и in-memory — переносить его через storage
// или URL нельзя), гарантированно терялся. Восстанавливался он silent refresh'ем уже на
// целевой вью, ценой лишнего round-trip /exchangeTokens. Здесь переход делает
// navigate() — документ не меняется, память живёт, round-trip не нужен.
import { navigate } from "/router.js";
import api from "/axios.js";
import { getFingerprintData } from "/meta_catcher.js";
import { setAccessToken } from "/inmemory.js";

function readCsrfToken() {
    const raw = document.cookie.split('; ').find(row => row.startsWith('XSRF-TOKEN='));
    return raw ? raw.split('=')[1] : null;
}

// Спиннер берём готовый из app.html (#global-spinner), а не рисуем свой в #app:
// у callback.html была собственная копия разметки и CSS крутилки, и держать вторую
// такую же внутри шелла — это ровно тот дубль, ради устранения которого страница и
// переезжает.
function showSpinner() {
    const el = document.getElementById('global-spinner');
    if (el) el.style.display = 'flex';
}

function hideSpinner() {
    const el = document.getElementById('global-spinner');
    if (el) el.style.display = 'none';
}

// Текст ошибки доносим до экрана входа query-параметром error: роутер отдаёт query
// в mount(params), а welcome.view.js уже читает params.error и показывает его в
// #response-div. Отдельный канал (storage, глобальная шина) заводить незачем.
function toWelcomeWithError(reason) {
    return navigate('/welcome?error=' + encodeURIComponent('OAuth: ' + reason));
}

export async function mount(params) {
    showSpinner();
    try {
        // code/state разбирает роутер из query — второй раз парсить location.search незачем.
        const code = params?.code;
        const state = params?.state;
        if (!code || !state) {
            console.warn('[callback.view] В query нет code/state, уходим на /welcome');
            await navigate('/welcome');
            return;
        }

        const meta = await getFingerprintData();

        const body = new URLSearchParams();
        body.append('code', code);
        body.append('state', state);
        body.append('FpComponents', JSON.stringify(meta.components));

        const response = await api.post('/verifylogin', body, {
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded',
                'X-CSRF-TOKEN': readCsrfToken(),
                'X-Fingerprint': meta.fingerprint,
                'X-SecureUUID': meta.secureUUID,
                'X-Client-Meta': JSON.stringify(meta.clientMeta)
            }
        });

        const data = response.data;
        if (data?.accessToken) {
            setAccessToken(data.accessToken);
        }
        if (!data?.redirectUri) {
            await toWelcomeWithError(data?.message || 'сервер не вернул адрес перехода');
            return;
        }
        await navigate(data.redirectUri);
    } catch (error) {
        console.error('[callback.view] Обмен кода на токены не удался:', error);
        await toWelcomeWithError(
            error?.response?.data?.message || error?.response?.data || error?.message || 'ошибка входа'
        );
    } finally {
        hideSpinner();
    }
}

export function unmount() {
    // Своих подписок и таймеров у вью нет, но спиннер — общий элемент шелла: если уйти,
    // не погасив его, оверлей останется висеть поверх следующего экрана.
    hideSpinner();
}
