// Трекинг STOMP-клиентов вью и их массовый disconnect при уходе с экрана.
// Без него SPA-навигация копит SockJS-соединения и дублирует подписки.

/**
 * Закрывает один клиент независимо от того, в каком состоянии его сокет.
 *
 * Раньше здесь безусловно звался client.disconnect(), и на не успевшем открыться соединении
 * это не работало вовсе. Порядок действий внутри stompjs 2.3.3 такой:
 *
 *     Client.prototype.disconnect = function(cb, headers) {
 *         this._transmit("DISCONNECT", headers);   // -> ws.send()
 *         this.ws.onclose = null;
 *         this.ws.close();                          // сюда уже не доходит
 *         this._cleanUp();
 *     };
 *
 * а SockJS на ещё не открытом сокете бросает из send():
 *
 *     if (this.readyState === SockJS.CONNECTING) {
 *         throw new Error('InvalidStateError: The connection has not been established yet');
 *     }
 *
 * То есть исключение вылетало ДО ws.close(), гасилось try/catch у вызывающего, ссылка на
 * клиент терялась вместе с очисткой списка — и соединение оставалось жить навсегда. Дозвонившись,
 * оно вызывало свой connect-колбэк и подписывалось на адреса уже мёртвой вью. Отключить его
 * было больше нечем: ссылки ни у кого не осталось.
 *
 * Окно для этого не микроскопическое. Замер на стенде: четыре сокета списка чатов открывались
 * 3, 5, 7 и 8 секунд после запроса — всё это время уход с экрана давал осиротевшее соединение.
 * За три перехода накапливалось 21 созданное соединение и НОЛЬ отправленных DISCONNECT, из них
 * три живых подписки на /mutual/chat/{id} — ровно то, отчего одно сообщение рисовалось трижды.
 */
function closeClient(client) {
    if (!client) {
        return;
    }
    const socket = client.ws;

    // Колбэки снимаем ПЕРВЫМ делом и в любом случае. Пока сокет в CONNECTING, stompjs держит
    // на нём свой onopen, который отправит CONNECT и следом SUBSCRIBE. Между решением закрыть
    // соединение и фактическим закрытием хендшейк может успеть завершиться — без снятия
    // onopen такое соединение подпишется уже по дороге в могилу.
    if (socket) {
        socket.onopen = null;
        socket.onclose = null;
        socket.onmessage = null;
        socket.onerror = null;
    }

    // Открытое соединение закрываем вежливо: DISCONNECT даёт серверу снять подписки сразу,
    // не дожидаясь, пока он заметит обрыв транспорта.
    if (client.connected) {
        try {
            client.disconnect(() => {});
            return;
        } catch (e) {
            // Не фатально: ниже всё равно закрываем сокет напрямую.
            console.warn("[stomp-lifecycle] disconnect failed, закрываю сокет напрямую", e);
        }
    }

    // Всё остальное — сокет в CONNECTING, CLOSING или уже CLOSED. close() умеет закрыть
    // незавершённый хендшейк, а на уже закрытом бросает ('SockJS has already been closed'),
    // поэтому под try.
    if (socket && typeof socket.close === "function") {
        try {
            socket.close();
        } catch (e) {
            console.warn("[stomp-lifecycle] close failed", e);
        }
    }
}

export function createStompRegistry() {
    const clients = [];
    return {
        add(client) { clients.push(client); return client; },
        disconnectAll() {
            for (const c of clients) {
                // Отдельного try здесь нет намеренно: closeClient не бросает сам, а глотать
                // его исключения снаружи означало бы вернуть ровно ту ошибку, из-за которой
                // соединения и утекали — сбой на одном клиенте молча пропускал остальных.
                closeClient(c);
            }
            clients.length = 0;
        }
    };
}
