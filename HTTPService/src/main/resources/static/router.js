// Клиентский роутер SPA на History API.
// mount(params) рисует экран в #app; unmount() гасит предыдущий (disconnect STOMP + очистка).
import { getAccessToken } from "/inmemory.js";

const routes = [];      // { pathname, loader }
let currentView = null;
let currentPath = null;

export function registerRoute(pathname, loader) {
    routes.push({ pathname, loader });
}

function findRoute(pathname) {
    return routes.find(r => r.pathname === pathname) || null;
}

async function renderRoute(path) {
    const url = new URL(path, location.origin);
    let route = findRoute(url.pathname);
    if (!route) {
        // неизвестный путь: если токена нет — на welcome, иначе на chatlist
        const fallback = getAccessToken() ? "/reactive/chatlist" : "/welcome";
        route = findRoute(fallback);
        history.replaceState({}, "", fallback);
    }
    // teardown предыдущей вью
    if (currentView && typeof currentView.unmount === "function") {
        try { currentView.unmount(); } catch (e) { console.error("[router] unmount failed", e); }
    }
    currentView = null;
    const app = document.getElementById("app");
    if (app) app.replaceChildren();

    const mod = await route.loader();
    currentView = mod;
    currentPath = location.pathname + location.search;
    const params = Object.fromEntries(new URL(location.href).searchParams.entries());
    await mod.mount(params);
}

export function navigate(path) {
    if (path === currentPath) return Promise.resolve();
    history.pushState({}, "", path);
    return renderRoute(path);
}

window.addEventListener("popstate", () => renderRoute(location.pathname + location.search));

export function start() {
    return renderRoute(location.pathname + location.search);
}
