// Трекинг STOMP-клиентов вью и их массовый disconnect при уходе с экрана.
// Без него SPA-навигация копит SockJS-соединения и дублирует подписки.
export function createStompRegistry() {
    const clients = [];
    return {
        add(client) { clients.push(client); return client; },
        disconnectAll() {
            for (const c of clients) {
                try {
                    if (c && typeof c.disconnect === "function") c.disconnect(() => {});
                    else if (c && c.ws && typeof c.ws.close === "function") c.ws.close();
                } catch (e) {
                    console.error("[stomp-lifecycle] disconnect failed", e);
                }
            }
            clients.length = 0;
        }
    };
}
