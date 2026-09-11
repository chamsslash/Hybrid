import api from "/axios.js";
import { navigate } from "/router.js";
import { ensureAccessToken } from "/auth.js";
import { imageTag, hydrateImages, releaseImages } from "/image_loader.js";
import { createStompRegistry } from "/stomp-lifecycle.js";
import { showToast } from "/toast.js";

// Экран профиля (beads ehe): просмотр своих аватарки и ника, смена ника, пароля и аватарки.
//
// Отдельный роут, а не блок на странице списка чатов: здесь три формы редактирования и
// загрузка файла, встроенные в список они отжали бы сами чаты вниз и превратили бы главный
// экран в форму настроек. Повторяем приём createchat — свой роут, кнопка «назад» к списку.
//
// Каждый блок сохраняется отдельной кнопкой; общего «сохранить всё» нет — операции
// независимы и имеют разные исходы.

const PROFILE_HTML = `
    <div class="post-feed" style="max-width: 480px;">
        <button type="button" id="back-btn" class="back-button" aria-label="Назад к списку чатов">←</button>

        <h2 style="text-align: center;">Профиль</h2>

        <div class="post" style="display: flex; align-items: center; gap: 16px;">
            <div id="profile-avatar" class="chat-avatar-container"></div>
            <div class="user-info">
                <span class="user-name" id="profile-username"></span>
                <span class="user-id" id="profile-userid"></span>
            </div>
        </div>

        <form id="username-form" class="add-comment">
            <label>
                Новый ник
                <input type="text" id="username-input" name="username" placeholder="Новый ник" required>
            </label>
            <button type="submit">Сменить ник</button>
        </form>

        <form id="avatar-form" class="add-comment" enctype="multipart/form-data">
            <label>
                Новая аватарка
                <input type="file" id="avatar-input" name="file" accept="image/*" required>
            </label>
            <button type="submit">Загрузить аватарку</button>
        </form>

        <div id="password-section"></div>
    </div>
`;

const PASSWORD_FORM_HTML = `
    <form id="password-form" class="add-comment">
        <label>
            Текущий пароль
            <input type="password" id="current-password" name="currentPassword" required>
        </label>
        <label>
            Новый пароль
            <input type="password" id="new-password" name="newPassword" required>
        </label>
        <button type="submit">Сменить пароль</button>
    </form>
`;

// Аккаунтам без пароля форма не показывается: подтверждать смену нечем, а ЗАДАТЬ пароль
// Google-аккаунту — отдельный сценарий аутентификации, в эту фичу он не входит.
const GOOGLE_NOTICE_HTML = `
    <p class="post-content" style="color: #888;">Вход через Google — пароля у аккаунта нет.</p>
`;

// Человеческие сообщения на коды ошибок сервера. Ключи — ровно то, что кладёт в поле
// "error" ApiController.
const ERROR_MESSAGES = {
    USERNAME_TAKEN: "Такой ник уже занят",
    USERNAME_BLANK: "Ник не может быть пустым",
    PASSWORD_BLANK: "Новый пароль не может быть пустым",
    WRONG_CURRENT_PASSWORD: "Неверный текущий пароль",
    NO_PASSWORD_ON_ACCOUNT: "У аккаунта нет пароля — вход через Google",
    USER_NOT_FOUND: "Пользователь не найден",
};

let ac = null;
let stomp = null;
let user_id = null;

function messageFor(error, fallback) {
    const code = error?.response?.data?.error;
    return ERROR_MESSAGES[code] || fallback;
}

function renderAvatar(objectKey) {
    const container = document.getElementById("profile-avatar");
    if (!container) return;
    // 'pending' означает «байты ещё едут в MinIO» — рисуем спиннер, как в списке чатов.
    if (objectKey === "pending") {
        container.innerHTML = policy.createHTML(`<div class="spinner-avatar"></div>`);
        return;
    }
    container.innerHTML = policy.createHTML(imageTag(objectKey, "chat-avatar", "аватарка"));
    hydrateImages(container);
}

// Догон аватарки, потерянной мимо STOMP (follow-on к beads ehe).
//
// В списке чатов (chatlist.view.js) добор нужен из-за гонки «событие опубликовано ДО
// того, как встала подписка»: создатель чата попадает на экран списка уже после того,
// как STOMP-событие о его картинке ушло, а STOMP кадры до SUBSCRIBE не переигрывает.
// Здесь этой гонки нет: подписка на /mutual/user_image/${user_id} встаёт при
// монтировании экрана — задолго до того, как пользователь вообще нажмёт «Загрузить
// аватарку». Добор нужен по другой причине — на случай ПОТЕРИ события где-то в
// пайплайне MinIO -> Kafka -> ImageUrlPersistenceService -> STOMP (упал консьюмер,
// оборвалось соединение между загрузкой и рассылкой): подписка жива, но слушать ей
// нечего, и спиннер крутится до ручной перезагрузки страницы. Поэтому и запускается
// добор не при монтировании (там подписке ещё нечего было прозевать), а сразу после
// успешной загрузки — там же, где рисуется спиннер.
const PENDING_RETRY_DELAYS_MS = [1000, 2000, 4000, 8000];
let pendingRetryTimer = null;

function schedulePendingAvatarRefresh(attempt = 0) {
    if (attempt >= PENDING_RETRY_DELAYS_MS.length) return;
    // Спиннера уже нет — событие успело дойти и renderAvatar его отрисовал, добирать нечего.
    if (!document.querySelector(".spinner-avatar")) return;

    pendingRetryTimer = setTimeout(async () => {
        pendingRetryTimer = null;
        try {
            const me = (await api.get("/api/me")).data;
            // 'pending' и пустая строка — ключа ещё нет, байты всё ещё едут по пайплайну.
            if (me.imageUrl && me.imageUrl !== "pending") {
                renderAvatar(me.imageUrl);
            }
        } catch (e) {
            // Не обрываем цепочку: разовый сбой сети не повод бросать оставшиеся попытки.
            console.error("pending avatar refresh failed:", e);
        }
        schedulePendingAvatarRefresh(attempt + 1);
    }, PENDING_RETRY_DELAYS_MS[attempt]);
}

function renderPasswordSection(hasPassword) {
    const section = document.getElementById("password-section");
    if (!section) return;
    section.innerHTML = policy.createHTML(hasPassword ? PASSWORD_FORM_HTML : GOOGLE_NOTICE_HTML);
    if (!hasPassword) return;

    document.getElementById("password-form").addEventListener("submit", async (e) => {
        e.preventDefault();
        const current = document.getElementById("current-password");
        const next = document.getElementById("new-password");
        try {
            await api.post("/api/profile/password", {
                currentPassword: current.value,
                newPassword: next.value,
            });
            // Поля пароля очищаются в любом исходе: оставлять введённый пароль в DOM
            // незачем ни после успеха, ни после ошибки.
            current.value = "";
            next.value = "";
            showToast("Пароль изменён, остальные сессии завершены", "success");
        } catch (error) {
            current.value = "";
            next.value = "";
            console.error("[profile] смена пароля не удалась", error);
            showToast(messageFor(error, "Не удалось сменить пароль"), "error");
        }
    }, { signal: ac.signal });
}

function connectStomp(token) {
    // Один клиент на одну подписку. Эндпоинт /MutualImagesConn до сих пор не использовался
    // ни одной вью; StompConfig регистрирует все шесть адресов одним циклом с одинаковой
    // конфигурацией, так что выбор эндпоинта ни на что не влияет — доступ разграничивает
    // StompAuthChannelInterceptor по destination подписки.
    const profileStomp = stomp.add(Stomp.over(new SockJS("/MutualImagesConn")));
    profileStomp.connect({ Authorization: `Bearer ${token}` }, () => {
        // Адрес публикуется сервером с самого начала (ChatBoxStompController), но до этой
        // фичи его никто не слушал — событие уходило в пустоту. Семейство
        // /mutual/user_image/ уже лежит в PER_USER_PREFIXES интерцептора, поэтому подписаться
        // на чужой адрес нельзя.
        profileStomp.subscribe(`/mutual/user_image/${user_id}`, (msg) => {
            const event = JSON.parse(msg.body);
            renderAvatar(event.objectKey);
            showToast("Аватарка обновлена", "success");
        });
    });
}

export async function mount(params) {
    ac = new AbortController();
    stomp = createStompRegistry();
    user_id = null;
    pendingRetryTimer = null;

    const app = document.getElementById("app");
    app.innerHTML = policy.createHTML(PROFILE_HTML);

    // Возврат явным маршрутом, а не history.back(): на профиль заходят и по прямой ссылке,
    // и тогда в истории возвращаться некуда. Родитель у экрана один — список чатов.
    document.getElementById("back-btn").addEventListener("click", () => {
        navigate("/reactive/chatlist");
    }, { signal: ac.signal });

    // Шелл /reactive/profile публичен на ingress — иначе обычная навигация (F5, закладка,
    // прямая ссылка) не несёт Authorization и nginx отдаёт голую страницу 401 вместо
    // приложения. Доступ проверяем на клиенте, как в createchat.view.js.
    let token;
    try {
        token = await ensureAccessToken();
    } catch (e) {
        console.error("profile bootstrap failed: нет живой сессии", e);
        await navigate("/welcome");
        return;
    }

    let me;
    try {
        me = (await api.get("/api/me")).data;
    } catch (e) {
        console.error("profile bootstrap failed:", e);
        showToast("Не удалось загрузить профиль", "error");
        return;
    }

    user_id = String(me.userId);
    document.getElementById("profile-username").textContent = me.username || "";
    document.getElementById("profile-userid").textContent = `ID: ${user_id}`;
    document.getElementById("username-input").value = me.username || "";
    renderAvatar(me.imageUrl);
    renderPasswordSection(Boolean(me.hasPassword));

    document.getElementById("username-form").addEventListener("submit", async (e) => {
        e.preventDefault();
        const input = document.getElementById("username-input");
        const newUsername = input.value;
        try {
            await api.post("/api/profile/username", { username: newUsername });
            // Отображаемый ник обновляем сами — перезагрузка не нужна.
            document.getElementById("profile-username").textContent = newUsername;
            showToast("Ник изменён", "success");
        } catch (error) {
            // Поле НЕ очищаем: человек поправит один символ, а не наберёт всё заново.
            console.error("[profile] смена ника не удалась", error);
            showToast(messageFor(error, "Не удалось сменить ник"), "error");
        }
    }, { signal: ac.signal });

    document.getElementById("avatar-form").addEventListener("submit", async (e) => {
        e.preventDefault();
        const input = document.getElementById("avatar-input");
        if (!input.files || input.files.length === 0) {
            showToast("Выберите файл", "error");
            return;
        }
        const formData = new FormData();
        formData.append("file", input.files[0]);
        try {
            await api.post("/reactive/api/avatar", formData);
            // Спиннер до прихода события: ответ 202 означает лишь «байты уехали в MinIO».
            // Ключ появится в БД после прохода Kafka -> ImageUrlPersistenceService, и тогда
            // же придёт событие на /mutual/user_image/{userId}.
            renderAvatar("pending");
            schedulePendingAvatarRefresh();
            input.value = "";
        } catch (error) {
            // Прежняя картинка остаётся на месте — renderAvatar здесь не зовём.
            console.error("[profile] загрузка аватарки не удалась", error);
            showToast("Не удалось загрузить аватарку", "error");
        }
    }, { signal: ac.signal });

    connectStomp(token);
}

export function unmount() {
    // Таймер догона снимаем раньше остального: иначе уже отмонтированная вью сходила бы
    // в /api/me и полезла бы в узлы (#profile-avatar), которых в DOM больше нет.
    if (pendingRetryTimer) {
        clearTimeout(pendingRetryTimer);
        pendingRetryTimer = null;
    }
    if (ac) ac.abort();
    ac = null;
    // blob-URL живут до отзыва — иначе вкладка копит их при каждом переходе.
    releaseImages();
    if (stomp) stomp.disconnectAll();
    stomp = null;
    user_id = null;
}
