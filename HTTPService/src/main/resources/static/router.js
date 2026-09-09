// Клиентский роутер SPA на History API.
// mount(params) рисует экран в #app; unmount() гасит предыдущий (disconnect STOMP + очистка).
import { getAccessToken } from "/inmemory.js";

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

export function start() {
    currentPath = location.pathname + location.search;
    return renderRoute(currentPath);
}
