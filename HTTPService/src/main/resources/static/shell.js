// Навигационный рельс app-shell'а.
//
// Рельс — единственная часть интерфейса, которая переживает навигацию: разметка
// лежит в app.html снаружи #app, поэтому replaceChildren() роутера её не трогает.
// Этот модуль отвечает ровно за две вещи: показать/скрыть рельс и подсветить
// текущий пункт, плюс нарисовать карточку пользователя внизу.
import api from "/axios.js";
import { ensureAccessToken } from "/auth.js";
import { imageTag, hydrateImages, pinImage } from "/image_loader.js";
import { navigate } from "/router.js";

// `policy` — глобальная Trusted Types policy из trusted_policy.js (обычный <script>,
// подключён в app.html до app.js), как в остальных вью.

// Маршруты, на которых рельса нет: до логина показывать навигацию не по чему.
// Решение принимается по имени маршрута, а НЕ по наличию access-токена: на свежей
// вкладке токена в памяти ещё нет (inmemory.js), и проверка по нему успевала бы
// мигнуть рельсом на экране входа, пока не отработает первый refresh.
const PUBLIC_ROUTES = new Set(["/welcome", "/registerpage", "/authcallback"]);

let mePromise = null;
// Последние известные данные пользователя. Держим отдельно от промиса, потому
// что карточку нужно уметь перерисовать по месту — профиль меняет ник и
// аватарку, а рельс показывает их же и живёт дольше экрана профиля.
let meCache = null;
let userRendered = false;
let navWired = false;

/**
 * /api/me один раз на вкладку.
 *
 * Токен берётся ПЕРЕД запросом по той же причине, по которой это делает
 * chatlist.view.js: на свежей вкладке его в памяти нет, запрос ушёл бы без
 * Authorization, заведомо получил 401 и разбудил обмен через интерцептор —
 * лишний round-trip ради токена, который всё равно нужен явно.
 *
 * До этого модуля /api/me дёргала вью списка чатов, то есть на КАЖДОМ возврате
 * к списку. Теперь запрос один, а список берёт его результат отсюда.
 */
export function loadMe() {
    if (!mePromise) {
        const attempt = ensureAccessToken()
            .then(() => api.get("/api/me").then(r => r.data))
            .then((me) => { meCache = me; return me; });
        mePromise = attempt;
        // Неудачу не кэшируем: иначе один сетевой сбой оставил бы вкладку без
        // данных пользователя до перезагрузки. Своего обработчика отказа вешать
        // нельзя молча — отказ обязан доехать до вызывающего, поэтому здесь
        // только сброс ссылки.
        attempt.catch(() => {
            if (mePromise === attempt) mePromise = null;
        });
    }
    return mePromise;
}

function wireNav() {
    if (navWired) return;
    navWired = true;
    document.querySelectorAll("#rail .rail-item").forEach((el) => {
        el.addEventListener("click", () => navigate(el.dataset.route));
    });
    // Слушатели не снимаются никогда — узлы рельса живут столько же, сколько
    // документ, и второй раз не создаются (защита флагом выше).
}

/** Рисует карточку по уже имеющимся данным. Идемпотентна: зовётся и при первом
 *  показе, и после каждой правки профиля. */
function paintUser(me) {
    const box = document.getElementById("rail-user");
    if (!box) return;
    box.innerHTML = policy.createHTML(`
            <div class="rail-user-avatar">${imageTag(me.imageUrl, "chat-avatar", "моя аватарка")}</div>
            <span class="rail-user-name"></span>
            <span class="rail-user-chevron" aria-hidden="true">›</span>
        `);
    box.querySelector(".rail-user-name").textContent = me.username || "";
    box.setAttribute("role", "button");
    box.setAttribute("tabindex", "0");
    box.setAttribute("aria-label", "Мой профиль");
    hydrateImages(box);
    // Аватарка рельса живёт дольше любой вью, а releaseImages() в unmount
    // вью отзывает blob-URL'ы всего кэша разом. Без закрепления первый же
    // переход со списка в чат оставлял бы в рельсе битую картинку.
    pinImage(me.imageUrl);

    box.onclick = () => navigate("/reactive/profile");
    box.onkeydown = (e) => {
        if (e.key === "Enter" || e.key === " ") {
            e.preventDefault();
            navigate("/reactive/profile");
        }
    };
}

function renderUser() {
    if (userRendered) return;
    userRendered = true;
    loadMe().then(paintUser).catch((e) => {
        // Нет сессии — рельс всё равно вот-вот уедет вместе с уходом на /welcome.
        console.warn("[shell] карточка пользователя не нарисована:", e);
        userRendered = false;
    });
}

/**
 * Правка данных пользователя, пришедшая не от нас (экран профиля сменил ник или
 * аватарку).
 *
 * Без этого рельс показывал СТАРОЕ значение до перезагрузки страницы: /api/me
 * кэшируется на вкладку, а карточка рисовалась ровно один раз. Выглядело так,
 * будто смена ника не прошла — на экране профиля новый, в навигации рядом
 * старый, и какой из них настоящий, пользователю неоткуда узнать.
 *
 * Перезапрашивать /api/me здесь не нужно: сервер уже подтвердил правку, а
 * лишний круг вернул бы ровно то, что мы и так знаем.
 */
export function updateShellUser(patch) {
    if (!meCache) return;
    meCache = { ...meCache, ...patch };
    const fresh = meCache;
    mePromise = Promise.resolve(fresh);
    paintUser(fresh);
}

/** Состояние рельса под текущий маршрут. Зовётся роутером на каждом переходе. */
// Подэкраны, у которых нет своего пункта в рельсе: подсвечивают раздел-родитель.
// Без этого на форме создания чата и в самом чате не подсвечено ничего, и рельс
// выглядит так, будто мы вне всех разделов.
const PARENT_ROUTE = {
    "/reactive/createchat": "/reactive/chatlist",
    "/reactive/chat": "/reactive/chatlist",
};

export function syncShell(pathname) {
    const hidden = PUBLIC_ROUTES.has(pathname);
    document.body.classList.toggle("shell-off", hidden);

    const section = PARENT_ROUTE[pathname] || pathname;
    document.querySelectorAll("#rail .rail-item").forEach((el) => {
        const active = el.dataset.route === section;
        el.classList.toggle("active", active);
        el.setAttribute("aria-current", active ? "page" : "false");
    });

    if (hidden) return;
    wireNav();
    renderUser();
}

/** Сброс при выходе из аккаунта: следующий вход рисует карточку заново. */
export function resetShell() {
    mePromise = null;
    meCache = null;
    userRendered = false;
    const box = document.getElementById("rail-user");
    if (box) box.replaceChildren();
}
