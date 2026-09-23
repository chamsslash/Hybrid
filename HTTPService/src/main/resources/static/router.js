// Клиентский роутер SPA на History API.
// mount(params) рисует экран в #app; unmount() гасит предыдущий (disconnect STOMP + очистка).
import { getAccessToken } from "/inmemory.js";
// Кольца здесь нет: auth.js тянет только inmemory.js и meta_catcher.js, обратно на
// роутер не смотрит.
import { ensureAccessToken } from "/auth.js";
// Импорт кольцевой: shell.js в свою очередь импортирует navigate отсюда. Это
// безопасно ровно потому, что обе стороны используют друг друга только внутри
// функций — `export function` поднимается, и к моменту первого вызова привязка
// уже разрешена. Вызвать syncShell на верхнем уровне этого модуля было бы
// нельзя: shell.js на тот момент ещё не инициализирован.
import { syncShell } from "/shell.js";

const routes = [];      // { pathname, loader }
let currentView = null;

// Адрес, КУДА идём. Обновляется синхронно в navigate(), до первого await.
//
// Раньше это присваивание жило внутри renderRoute, после `await route.loader()`, и дедупликация
// в navigate() из-за этого не работала: между кликом и обновлением значения оставалось окно
// длиной в динамический import(), а два клика в это окно проходили проверку оба и запускали
// renderRoute параллельно сам с собой. Состояние вью — модульные переменные (см. chat.view.js),
// поэтому два одновременных mount() затирали друг другу клиентов STOMP и оставляли по две
// живых подписки на один адрес: брокер шлёт копию на каждую, и сообщение рисовалось дважды.
let currentPath = null;

// Номер поколения рендера. Рендер, которого обогнали, обязан остановиться и НЕ монтировать:
// иначе он допишет свою вью поверх той, что уже нарисовал обогнавший.
let generation = 0;

export function registerRoute(pathname, loader) {
    routes.push({ pathname, loader });
}

function findRoute(pathname) {
    return routes.find(r => r.pathname === pathname) || null;
}

async function renderRoute(path) {
    const myGeneration = ++generation;

    const url = new URL(path, location.origin);
    let route = findRoute(url.pathname);
    if (!route) {
        // неизвестный путь: если токена нет — на welcome, иначе на chatlist
        const fallback = getAccessToken() ? "/reactive/chatlist" : "/welcome";
        route = findRoute(fallback);
        history.replaceState({}, "", fallback);
        // currentPath обязан следовать за фактическим адресом: иначе следующий navigate(fallback)
        // решит, что мы уже там, и не отрисует ничего.
        currentPath = fallback;
    }
    // Рельс переключается ДО загрузки модуля вью: он живёт снаружи #app и не
    // зависит от неё, а ждать динамический import() значило бы оставить
    // подсвеченным пункт, с которого уже ушли.
    // location здесь уже указывает на фактический адрес: navigate() сделал
    // pushState до вызова, popstate его сменил сам, а ветка фолбэка выше —
    // replaceState. Отдельно вычислять путь незачем.
    syncShell(location.pathname);

    // teardown предыдущей вью
    if (currentView && typeof currentView.unmount === "function") {
        try { currentView.unmount(); } catch (e) { console.error("[router] unmount failed", e); }
    }
    currentView = null;
    const app = document.getElementById("app");
    if (app) app.replaceChildren();

    const mod = await route.loader();

    // Пока грузился модуль, могла начаться навигация дальше. Тогда экран уже принадлежит ей —
    // монтироваться поверх нельзя.
    if (myGeneration !== generation) {
        return;
    }

    currentView = mod;
    const params = Object.fromEntries(new URL(location.href).searchParams.entries());
    await mod.mount(params);
}

export function navigate(path) {
    if (path === currentPath) return Promise.resolve();
    // Синхронно, до любого await — иначе повторный клик по тому же адресу не отсечётся.
    currentPath = path;
    history.pushState({}, "", path);
    return renderRoute(path);
}

window.addEventListener("popstate", () => {
    const path = location.pathname + location.search;
    currentPath = path;
    renderRoute(path);
});

// Экран входа — единственная точка, с которой холодная вкладка обязана сама сходить
// за сессией.
//
// Access-токен живёт только в памяти вкладки (inmemory.js), поэтому после её закрытия
// его нет НИКОГДА, а refresh-кука в браузере ещё лежит: AuthCookies.REFRESH_TTL — 7
// дней, HttpOnly, SameSite=Strict, то есть закрытие вкладки её не трогает. Защищённые
// вью это учитывают и зовут ensureAccessToken() сами (shell.js, chatlist.view.js), а
// welcome.view.js рисовал форму логина безусловно — и человек, закрывший вкладку с
// живой сессией, при возврате на голый хост ("/" -> redirect на /welcome) видел вход,
// хотя сервер был готов выдать токен. Проверено на проде: /exchangeTokens из такой
// вкладки отвечает 200 с новым access-токеном.
//
// Пробуем ТОЛЬКО на /welcome. /registerpage намеренно не трогаем: с живой сессией
// можно осознанно пойти регистрировать второй аккаунт, и уводить оттуда — сюрприз.
// /authcallback у Google-входа свой обмен, ему посредник не нужен.
const ENTRY_ROUTE = "/welcome";
const RESTORED_ROUTE = "/reactive/chatlist";

// Потолок ожидания обмена. Не меньше четырёх секунд: /exchangeTokens на стороне
// сервера сам ждёт вердикт Gemini по отпечатку до AI_VERDICT_TIMEOUT (MVC_Service),
// и более тесный лимит рубил бы законное восстановление. Потолок при этом нужен: без
// него зависшая сеть оставила бы на экране входа вечный спиннер вместо формы, то есть
// починка одного случая сломала бы более частый.
const RESTORE_TIMEOUT_MS = 8000;

/**
 * Молчаливая попытка поднять сессию по refresh-куке.
 *
 * Отказ — штатный исход, а не ошибка: у анонима куки просто нет, и сервер отвечает 401.
 * Поэтому ничего не логируем как error и не показываем тост — дальше рисуется экран
 * входа, ровно как до этой правки.
 */
async function restoreSession() {
    const spinner = document.getElementById("global-spinner");
    if (spinner) spinner.style.display = "flex";
    try {
        await Promise.race([
            ensureAccessToken(),
            new Promise((_, reject) => setTimeout(() => reject(new Error("restore timeout")),
                                                 RESTORE_TIMEOUT_MS)),
        ]);
        // replaceState, а не pushState: /welcome не должен оставаться в истории, иначе
        // «Назад» с восстановленной сессии возвращает на форму входа.
        currentPath = RESTORED_ROUTE;
        history.replaceState({}, "", RESTORED_ROUTE);
    } catch {
        // сессии нет либо обмен не успел — показываем вход
    } finally {
        if (spinner) spinner.style.display = "none";
    }
}

export async function start() {
    currentPath = location.pathname + location.search;
    const url = new URL(currentPath, location.origin);
    // ?error=... ставит редирект после неудачного входа через Google. Сообщение об этой
    // неудаче — весь смысл такого перехода, и подменять его переходом в список чатов
    // нельзя: человек не узнает, почему его вернуло.
    if (url.pathname === ENTRY_ROUTE && !url.searchParams.has("error")) {
        await restoreSession();
    }
    return renderRoute(currentPath);
}
