import { navigate } from "/router.js";
import api from "/axios.js";
import { ensureAccessToken } from "/auth.js";
import { imageTag, hydrateImages, releaseImages } from "/image_loader.js";

// Вью создания чата (перенесена из static/chatcreate.js под контракт mount/unmount SPA-роутера).
//
// Участники выбираются из подсказок по началу ника (beads cdn). Раньше здесь был блок
// #userFields с кнопкой «+ Добавить пользователя» и N свободными текстовыми полями: ошибка
// в букве обнаруживалась только после отправки, а сервер молча создавал чат без выпавшего
// участника. Теперь свободного ввода нет вовсе — отправить можно только выбранных из
// выдачи, и это и есть гарантия, что в чат попадают существующие пользователи.

const CREATECHAT_HTML = `
    <div class="screen">
        <header class="screen-head">
            <!-- Кнопка остаётся, в отличие от профиля: создание чата — подэкран списка,
                 а не раздел навигации, и в рельсе пункта под него нет. -->
            <button type="button" id="back-btn" class="back-button" aria-label="Назад к списку чатов">←</button>
            <h1 class="screen-title">Новый чат</h1>
        </header>

        <form id="chatForm" class="form-card" enctype="multipart/form-data">

            <!-- name НЕ "title": DOMPurify (см. policy.createHTML/trusted_policy.js) по
                 умолчанию защищается от DOM clobbering и молча вырезает name="title" —
                 совпадение с document.title. Реальный ключ "title", который ждёт сервер
                 (WEBFLUX_Service.handleCreateChat), подставляется вручную при сабмите. -->
            <label class="field">
                <span class="field-label">Название</span>
                <input type="text" name="chatTitle" placeholder="Название чата" required>
            </label>

            <label class="field">
                <span class="field-label">Картинка чата</span>
                <input type="file" name="file" id="imageUpload" accept="image/*">
            </label>

            <div class="field">
                <span class="field-label">Участники</span>
                <div id="user-picker" class="user-picker">
                    <input type="text" id="userSearch" placeholder="Начните вводить ник"
                           autocomplete="off" role="combobox" aria-expanded="false"
                           aria-controls="user-suggestions" aria-autocomplete="list">
                    <ul id="user-suggestions" class="user-suggestions" role="listbox" style="display: none;"></ul>
                </div>
                <div id="user-chips" class="user-chips"></div>
            </div>

            <button type="submit" class="btn-submit">Создать чат</button>
        </form>

        <p id="result" class="form-result"></p>
    </div>
`;

// Сколько ждать после последнего нажатия, прежде чем идти за подсказками. Без задержки
// каждая буква — отдельный запрос в БД.
const SEARCH_DEBOUNCE_MS = 200;

let ac = null;
let searchTimer = null;
// Выбранные участники: userId -> { username, imageUrl }. Ключ — id, а не ник: два
// одинаковых ника после UNIQUE-индекса невозможны, но id всё равно устойчивее.
let selected = new Map();
// Индекс подсвеченного пункта выпадашки; -1 = не подсвечен ни один.
let activeIndex = -1;
// Последняя выдача, показанная пользователю. Нужна, чтобы Enter знал, что именно выбирать.
let currentSuggestions = [];

function suggestionsEl() {
    return document.getElementById("user-suggestions");
}

function closeSuggestions() {
    const list = suggestionsEl();
    if (!list) return;
    list.style.display = "none";
    list.replaceChildren();
    document.getElementById("userSearch")?.setAttribute("aria-expanded", "false");
    activeIndex = -1;
    currentSuggestions = [];
}

function highlight(index) {
    const list = suggestionsEl();
    if (!list) return;
    const items = list.querySelectorAll("li[data-user-id]");
    items.forEach((node, i) => {
        const on = i === index;
        node.classList.toggle("active", on);
        node.setAttribute("aria-selected", on ? "true" : "false");
    });
    activeIndex = index;
}

function renderSuggestions(users) {
    const list = suggestionsEl();
    if (!list) return;
    list.replaceChildren();
    currentSuggestions = users;

    if (users.length === 0) {
        // Пустая панель без объяснений выглядит как сломавшийся запрос — говорим прямо.
        const empty = document.createElement("li");
        empty.textContent = "Никого не найдено";
        empty.className = "suggestion-empty";
        list.appendChild(empty);
    } else {
        users.forEach((user, index) => {
            const item = document.createElement("li");
            item.dataset.userId = user.userId;
            item.textContent = user.username;
            item.className = "suggestion-item";
            item.setAttribute("role", "option");
            item.setAttribute("aria-selected", "false");
            // mousedown, а не click: click приходит уже после blur поля ввода, и к этому
            // моменту обработчик blur успел бы закрыть выпадашку.
            item.addEventListener("mousedown", (e) => {
                e.preventDefault();
                selectUser(user);
            }, { signal: ac.signal });
            item.addEventListener("mouseenter", () => highlight(index), { signal: ac.signal });
            list.appendChild(item);
        });
    }

    list.style.display = "block";
    document.getElementById("userSearch")?.setAttribute("aria-expanded", "true");
    highlight(users.length > 0 ? 0 : -1);
}

function renderChips() {
    const container = document.getElementById("user-chips");
    if (!container) return;
    container.replaceChildren();

    for (const [userId, user] of selected) {
        const chip = document.createElement("span");
        chip.className = "user-chip";
        chip.dataset.userId = userId;

        // Аватарки только у выбранных: в выпадашке их нет намеренно. Ключ приехал вместе с
        // подсказкой, поэтому дополнительного запроса за ним нет — а вот БАЙТЫ каждой
        // картинки тянутся отдельным авторизованным запросом (тег <img> не умеет послать
        // Authorization, см. image_loader.js). Три чипса — три запроса; десять подсказок на
        // каждую букву — десятки.
        chip.innerHTML = policy.createHTML(`
            ${imageTag(user.imageUrl, "chip-avatar", "avatar")}
            <span class="chip-name"></span>
            <button type="button" class="chip-remove" aria-label="Убрать участника">×</button>
        `);
        chip.querySelector(".chip-name").textContent = user.username;
        chip.querySelector(".chip-remove").addEventListener("click", () => {
            selected.delete(userId);
            renderChips();
            document.getElementById("userSearch")?.focus();
        }, { signal: ac.signal });

        container.appendChild(chip);
        hydrateImages(chip);
    }
}

function selectUser(user) {
    selected.set(String(user.userId), {
        username: user.username,
        imageUrl: user.imageUrl,
    });
    renderChips();
    const input = document.getElementById("userSearch");
    if (input) {
        input.value = "";
        // Фокус остаётся в поле: следующего участника добавляют сразу, без лишнего клика.
        input.focus();
    }
    closeSuggestions();
}

async function fetchSuggestions(prefix) {
    let response;
    try {
        response = await api.get("/api/usersearch", { params: { prefix } });
    } catch (e) {
        // Сбой подсказок не ломает форму: выпадашка просто не открывается, создание чата
        // остаётся доступным. Сервер на сбой отвечает 500, а не пустым списком, — именно
        // чтобы «никого не нашли» и «не смогли поискать» различались.
        console.warn("[createchat] не удалось получить подсказки", e);
        closeSuggestions();
        return;
    }

    // Ответ на устаревший префикс отбрасывается: медленный ответ на «ми» не должен
    // перезаписать свежий на «миша». Сравнивается префикс, на который пришёл ответ, с
    // текущим содержимым поля.
    const input = document.getElementById("userSearch");
    if (!input || input.value.trim() !== prefix) {
        return;
    }

    // Уже выбранные в выдаче не показываем — второй раз их не добавить.
    renderSuggestions((response.data || []).filter((u) => !selected.has(String(u.userId))));
}

function onSearchInput(event) {
    clearTimeout(searchTimer);
    const prefix = event.target.value.trim();
    if (prefix === "") {
        closeSuggestions();
        return;
    }
    searchTimer = setTimeout(() => fetchSuggestions(prefix), SEARCH_DEBOUNCE_MS);
}

function onSearchKeydown(event) {
    const list = suggestionsEl();
    const open = list && list.style.display === "block" && currentSuggestions.length > 0;

    if (event.key === "Escape") {
        closeSuggestions();
        return;
    }
    if (!open) return;

    if (event.key === "ArrowDown") {
        event.preventDefault();
        highlight((activeIndex + 1) % currentSuggestions.length);
    } else if (event.key === "ArrowUp") {
        event.preventDefault();
        highlight((activeIndex - 1 + currentSuggestions.length) % currentSuggestions.length);
    } else if (event.key === "Enter") {
        // preventDefault обязателен: без него Enter в поле поиска отправил бы форму.
        event.preventDefault();
        if (activeIndex >= 0) {
            selectUser(currentSuggestions[activeIndex]);
        }
    }
}

export async function mount(params) {
    ac = new AbortController();
    selected = new Map();
    activeIndex = -1;
    currentSuggestions = [];
    searchTimer = null;

    // Шелл /reactive/createchat публичен на ingress (beads 52u) — иначе обычная
    // навигация (F5, закладка, прямая ссылка) не несёт Authorization и nginx
    // отдаёт голую страницу 401 вместо приложения. Доступ проверяем на клиенте:
    // без живой refresh-сессии уводим на /welcome, чтобы аноним не видел форму.
    // Сама мутация защищена auth_request на /reactive/api/createchat.
    // Уводим клиентским переходом: /welcome — такой же роут этого же shell'а,
    // и терять тут нечего (ensureAccessToken упал — значит токена в памяти и не было),
    // а window.location.href стоил бы лишней полной перезагрузки app-shell.
    try {
        await ensureAccessToken();
    } catch (e) {
        console.error("createchat bootstrap failed:", e);
        await navigate("/welcome");
        return;
    }

    const app = document.getElementById("app");
    app.innerHTML = policy.createHTML(CREATECHAT_HTML);

    // Возврат явным маршрутом, а не history.back(): на форму создания чата заходят и по
    // прямой ссылке, и тогда в истории возвращаться некуда. Целевой экран у неё всегда
    // один — список чатов, откуда её и открывают.
    document.getElementById("back-btn").addEventListener("click", () => {
        navigate("/reactive/chatlist");
    }, { signal: ac.signal });

    const searchInput = document.getElementById("userSearch");
    searchInput.addEventListener("input", onSearchInput, { signal: ac.signal });
    searchInput.addEventListener("keydown", onSearchKeydown, { signal: ac.signal });

    // Клик мимо выпадашки закрывает её. Слушаем на document, потому что кликнуть могут
    // куда угодно; сам picker из проверки исключён, иначе клик по пункту закрывал бы
    // список раньше, чем срабатывал выбор.
    document.addEventListener("click", (e) => {
        if (!document.getElementById("user-picker")?.contains(e.target)) {
            closeSuggestions();
        }
    }, { signal: ac.signal });

    document.getElementById("chatForm").addEventListener("submit", async function (e) {
        e.preventDefault();

        const resultElement = document.getElementById("result");
        if (selected.size === 0) {
            resultElement.textContent = "Выберите хотя бы одного участника";
            resultElement.style.color = "red";
            return;
        }

        const formData = new FormData(e.target);
        formData.set('title', formData.get('chatTitle'));
        formData.delete('chatTitle');
        // Поле поиска в теле запроса не нужно — сервер о нём ничего не знает.
        formData.delete('userSearch');
        // Уходят те же поля userlistname с теми же именами, что и раньше: серверный
        // контракт handleCreateChat не меняется вовсе. Отправляются имена, а не id, ровно
        // по этой причине; неоднозначности нет, ники регистронезависимо уникальны
        // (UNIQUE-индекс ux_users_lower_name).
        for (const user of selected.values()) {
            formData.append('userlistname', user.username);
        }

        try {
            // api-интерцептор приложит Authorization и сделает refresh+retry на 401
            await api.post("/reactive/api/createchat", formData);
            navigate("/reactive/chatlist");
        } catch (error) {
            console.error('Ошибка отправки:', error);
            resultElement.textContent = error?.response?.data || "Ошибка создания чата";
            resultElement.style.color = "red";
        }
    }, { signal: ac.signal });
}

export function unmount() {
    clearTimeout(searchTimer);
    searchTimer = null;
    if (ac) ac.abort();
    ac = null;
    selected = new Map();
    currentSuggestions = [];
    activeIndex = -1;
    // Отзываем blob-URL аватарок чипсов — иначе браузер держит их живыми до закрытия
    // вкладки, как в остальных вью.
    releaseImages();
}
