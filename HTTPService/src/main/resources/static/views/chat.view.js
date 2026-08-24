import api from "/axios.js";
import { imageTag, hydrateImages, releaseImages } from "/image_loader.js";
import { ensureAccessToken } from "/auth.js";
import { navigate } from "/router.js";
import { createStompRegistry } from "/stomp-lifecycle.js";
import { formatMessageTimestamp } from "/timestamp_format.js";

// === SPA-вью страницы чата (beads 55/57/58) ===
// id/title — из params роутера (mount(params)), данные — из /api/chat, STOMP — c access-токеном.

const TYPING_TIMER = 1000;

const CHAT_HTML = `
<div class="chat-container">

    <div class="chat-header">
        <button type="button" id="back-btn" class="back-button" aria-label="Назад к списку чатов">←</button>
        <div class="chat-avatar-container" id="chat-header-avatar"></div>
        <span id="chat-title"></span>
        <div id="typing-indicator"></div>
    </div>

    <div class="chat-messages" id="chatMessages"></div>

    <div class="chat-input">
        <input type="text" id="messageInput" placeholder="Type your message...">
        <button id="sendBtn" type="button">Send</button>
        <button id="aiBtn" type="button">Помощь AI</button>
        <!-- Панель подсказки живёт ВНУТРИ .chat-input, а не рядом с ним. Её CSS —
             position: absolute; bottom: calc(100% + 8px) — рассчитан на то, что
             содержащим блоком будет строка ввода: «8px над полем ввода». .chat-input
             для этого уже объявлен position: relative. Пока панель была соседом,
             ближайшего позиционированного предка у неё не было, отсчёт шёл от body,
             и 100% превращались в высоту документа: панель уезжала за верхний край
             экрана (замерено вживую: top: -145px при высоте вьюпорта 551px).
             Ответ AI при этом приходил нормально (200 и осмысленный текст) и честно
             отрисовывался в DOM — пользователь просто никогда его не видел. -->
        <div id="aiSuggestionPanel" class="ai-suggestion-panel" style="display: none;"></div>
    </div>
    <select id="aiUserSelect">
        <option value="">Выберите пользователя для AI-ответа</option>
    </select>
</div>
`;

let chat_id = null;
let chat_title = '';

let user_id = null;
let user_name = null;
let user_image = null;

let typingUsers = [];
let stompClient = null;
let statusStomp = null;
let imagesStomp = null;
let typingTimeout = null;

// Текст последнего отправленного сообщения (beads isf). Поле ввода чистится сразу после
// send, потому что STOMP ничего не подтверждает; если сервер отказал (нас убрали из чата,
// MessegerParody недоступен), текст возвращается сюда из отбивки на /private/{user_id}.
// Корреляции «эхо подтвердило доставку → можно чистить» здесь нет намеренно: она требует
// сквозного идентификатора сообщения, которого в контракте нет.
let pendingText = '';

// Ретрай подписок при SUBSCRIPTION_UNAVAILABLE (изначально beads 8wh, обобщено на все три
// канала в 8r7). Сообщения/typing/аватарка чата живут на трёх разных STOMP-соединениях
// (stompClient/statusStomp/imagesStomp, см. connectStomp) с разными колбэками, и сервер
// роняет их подписки НЕЗАВИСИМО (StompAuthChannelInterceptor.PER_CHAT_PREFIXES). Бюджет
// попыток и таймер повтора держим РАЗДЕЛЬНО на канал в channelState — общий счётчик
// означал бы, что один сбойный канал сжигает лимит попыток остальным.
//
// Лимит в 3 попытки на канал действует на один заход в чат — сбрасывается только в
// unmount(), не при удачной подписке: успех SUBSCRIBE в STOMP не наблюдаем без
// receipt-заголовка, а вводить эту машинерию ради счётчика не стали. Это
// самосогласованно: при исчерпании лимита канала сообщений пользователю предлагают
// обновить страницу, а обновление проходит через unmount()/mount(), которые пересобирают
// channelState с нуля.
const SUBSCRIBE_BACKOFF_MS = [1000, 2000, 4000];

// state.subscription — вторая линия защиты от дублей на канал (beads 8wh, F1): даже если
// фильтр по destination в handleChatError когда-нибудь обойдут, subscribeChannel() не даст
// двух живых подписок на один адрес.
// state.retryTimer — id таймера последнего запланированного повтора. Без сохранения и
// явного clearTimeout в unmount() таймер переживает уход со страницы: клиенты — модульные
// переменные и в unmount() не обнуляются, а следующий connectStomp() присвоит им новые
// подключённые клиенты — тогда guard `client.connected` в setTimeout-колбэке пропустит
// просроченный таймер, и чат получит вторую подписку на тот же (или уже другой) адрес.
const channelState = {
    chat: { subscription: null, retries: 0, retryTimer: null },
    typing: { subscription: null, retries: 0, retryTimer: null },
    chat_image: { subscription: null, retries: 0, retryTimer: null },
};

let stomp = null;
let ac = null;

// key — MinIO objectKey (напр. userimage/42/uuid.png). Разметка отдаёт заглушку с меткой
// data-image-key, байты подставляет hydrateImages через axios (beads gs2): тег <img> не умеет
// послать Authorization, и такой запрос отбивался 401 ещё на ingress.
function avatarHtml(key) {
    return imageTag(key, 'chat-avatar', 'chat avatar');
}

function appendChatMessage({ user_id: senderId, username, timestamp, text, imageurl, image_url }) {
    const container = document.getElementById('chatMessages');
    const msg = document.createElement('div');
    msg.classList.add('message');
    msg.classList.add(String(senderId) === String(user_id) ? 'user' : 'bot');

    const img = imageurl ?? image_url;
    // Шапка — flex-строка, а не float (beads 9kn): пузырь сообщения сжимается по контенту
    // (.message max-width: 78%), поэтому float: right у времени не находил свободного места
    // и приклеивался вплотную к ID — «ID: 122 авг., 13:05». Время отжимается вправо через
    // margin-left: auto, а gap гарантирует зазор даже когда строка забита под завязку.
    // В разметку подставляем только то, что генерируем сами; текст сообщения и имя
    // отправителя кладём текстовыми узлами. Раньше они интерполировались в HTML, и
    // сообщение рендерилось как разметка: набранное «<b>жирный</b>» показывалось жирным
    // вместо того, что человек набрал, а «<img src="https://чужой-хост/x.gif">» заставляло
    // браузер каждого участника сходить на указанный отправителем адрес. Исполнения скрипта
    // тут не было (DOMPurify в policy.createHTML вырезает onerror и <script>), но санитайзер
    // и не обязан спасать — сообщения в этом приложении везде обычный текст: поле ввода
    // без форматирования, а превью в списке чатов уже проставляется через textContent.
    msg.innerHTML = policy.createHTML(`
        <div class="message-header">
            <div class="message-avatar">${avatarHtml(img)}</div>
            <strong class="message-username"></strong>
            <strong class="message-senderid"></strong>
            <span class="message-time">${formatMessageTimestamp(timestamp)}</span>
        </div>
        <div><span class="message-text"></span></div>
    `);
    msg.querySelector('.message-username').textContent = username || 'anon';
    msg.querySelector('.message-senderid').textContent = `ID: ${senderId || 'anon'}`;
    msg.querySelector('.message-text').textContent = text ?? '';

    container.appendChild(msg);
    hydrateImages(msg);
    container.scrollTop = container.scrollHeight;
}

function renderHeader(data) {
    document.getElementById('chat-title').textContent = data.title || chat_title;
    const headerAvatar = document.getElementById('chat-header-avatar');
    headerAvatar.innerHTML = policy.createHTML(
        data.chatImageUrl === 'pending'
            ? `<div class="spinner-avatar"></div>`
            : avatarHtml(data.chatImageUrl)
    );
    hydrateImages(headerAvatar);
}

function renderMembers(members) {
    const select = document.getElementById('aiUserSelect');
    for (const member of members || []) {
        if (member === user_name) continue;
        const opt = document.createElement('option');
        opt.value = member;
        opt.textContent = member;
        select.appendChild(opt);
    }
}

// --- typing ---
function debounce(fn, delay) {
    let timer;
    return function (...args) {
        clearTimeout(timer);
        timer = setTimeout(() => fn.apply(this, args), delay);
    };
}

function sendTypingStatus(status) {
    if (!stompClient) return;
    // chat_id теперь в адресе — сервер берёт его оттуда и игнорирует тело (beads g9x).
    stompClient.send(`/app/chat/user_statuses/${chat_id}`, {}, JSON.stringify({ status }));
}

function showTypingIndicator(userName) {
    if (!typingUsers.includes(userName)) {
        typingUsers.push(userName);
        renderTyping();
    }
}

function hideTypingIndicator(userName) {
    const index = typingUsers.indexOf(userName);
    if (index !== -1) {
        typingUsers.splice(index, 1);
        renderTyping();
    }
}

function renderTyping() {
    const indicator = document.getElementById('typing-indicator');
    if (typingUsers.length === 0) {
        indicator.textContent = '';
    } else if (typingUsers.length === 1) {
        indicator.textContent = `${typingUsers[0]} печатает...`;
    } else {
        indicator.textContent = `${typingUsers.join(', ')} печатают...`;
    }
}

// --- messages ---
function sendChatMessage() {
    const input = document.getElementById('messageInput');
    const text = input.value.trim();
    if (!text) return;
    // username/chat_id/user_id больше не шлём: сервер проставляет их сам из принципала
    // и адреса, присланные значения игнорируются (beads g9x).
    const message = {
        text: text,
        imageurl: user_image
    };
    stompClient.send(`/app/chat/send/${chat_id}`, {}, JSON.stringify(message));
    pendingText = text;
    input.value = "";
}

// Отбивка отказа с /private/{user_id} (beads isf). Раньше отказ на SEND был одним log.warn
// на сервере: сообщение не доходило никуда, а поле ввода уже было очищено — текст пропадал
// бесследно, и пользователь об этом не узнавал.
function handleChatError(msg) {
    const error = JSON.parse(msg.body);
    if (error.type !== 'error') return;

    // Сервер не смог проверить членство и уронил SUBSCRIBE, не разрывая сессию (beads 8wh).
    // Это не отказ в доступе, а отсутствие ответа, поэтому повторяем сами, а не показываем
    // пользователю «нет доступа».
    if (error.code === 'SUBSCRIPTION_UNAVAILABLE') {
        // Отбивка могла прийти не за наш канал (beads 8wh, F1 → обобщено в 8r7):
        // /private/{user_id} общий — на него падают отказы ЛЮБОГО из трёх каналов этого же
        // чата и, если у пользователя открыто несколько вкладок, вообще чужого чата.
        // channelForDestination матчит по точному адресу ТЕКУЩЕГО чата; чужой чат и
        // отсутствие destination (старый клиент/сервер) трактуем одинаково — молча
        // пропускаем, без тоста и без повтора: молчание лучше дублей или повтора не туда.
        const name = channelForDestination(error.destination);
        if (!name) {
            return;
        }
        const state = channelState[name];
        // Повтор уже запланирован (beads 8wh, R2) — вторая отбивка по ЭТОМУ каналу
        // (например, из соседней вкладки того же чата на общий /private/{userId}) не должна
        // списывать из бюджета ещё одну попытку: фактически всё равно выполнится ровно один
        // повтор, а лишний инкремент retries раньше времени исчерпывал бы лимит в 3
        // попытки. До аналогичной правки для канала сообщений (8wh) чистка через
        // clearTimeout перед новым setTimeout гасила уже ОПЛАЧЕННЫЙ таймер, то есть бюджет
        // считал попытки, которые физически не происходили. Счётчик и таймер — per-канал в
        // channelState, поэтому сбойный typing не трогает бюджет chat/chat_image и наоборот.
        if (state.retryTimer) {
            return;
        }
        if (state.retries < SUBSCRIBE_BACKOFF_MS.length) {
            const delay = SUBSCRIBE_BACKOFF_MS[state.retries];
            state.retries += 1;
            // Тост на КАЖДУЮ попытку показываем только для канала сообщений (beads 8r7).
            // Это единственный канал, чей сбой пользователь замечает сразу — сообщения
            // просто не приходят. typing/chat_image — фоновые индикаторы (кто печатает,
            // живая аватарка чата): их временная недоступность не мешает пользоваться
            // чатом, и тост на каждую попытку по ним был бы просто шумом без пользы.
            if (name === 'chat') {
                showToast('Восстанавливаем связь с чатом…', 'info');
            }
            state.retryTimer = setTimeout(() => {
                // Без сброса здесь guard выше залипнет навсегда после первого же повтора —
                // ни одна следующая отбивка по этому каналу не запланирует новый таймер
                // (beads 8wh, R2).
                state.retryTimer = null;
                const client = CHANNELS[name].getClient();
                if (client && client.connected) {
                    subscribeChannel(name);
                }
            }, delay);
        } else if (name === 'chat') {
            showToast('Не удалось открыть чат — обновите страницу', 'error');
        } else {
            // Терминальный провал typing/chat_image (beads 8r7) НЕ показываем error-тостом:
            // это фоновые индикаторы, чат ими не блокируется (сообщения продолжают ходить
            // по своему каналу), а тревожный тост про «печатает» или аватарку вводил бы
            // пользователя в заблуждение насчёт того, что реально сломано. Тихо логируем;
            // подписка восстановится сама при следующем заходе в чат (mount() пересобирает
            // все три канала заново).
            console.warn(`[chat] не удалось восстановить подписку "${name}" после ${SUBSCRIBE_BACKOFF_MS.length} попыток`);
        }
        return;
    }

    showToast(error.message || 'Сообщение не отправлено', 'error');

    const input = document.getElementById('messageInput');
    // Не затираем то, что пользователь успел набрать заново, пока летела отбивка.
    if (input && pendingText && !input.value.trim()) {
        input.value = pendingText;
    }
    pendingText = '';
}

// Канал уже привязан к открытому чату (/mutual/chat_image/{chat_id}, beads bwh), поэтому
// искать элемент по targetId не нужно — обновляем аватарку в шапке этого чата.
// Прежний updateImage(msg, prefix) собирал id как `${prefix}${message.targetId}` и искал
// chat_img-{id} / img-{id}. Таких id нет ни в CHAT_HTML, ни в appendChatMessage: шапка —
// это #chat-header-avatar, а аватарки сообщений рисуются инлайн, без id. Обработчик всегда
// получал target === null и молча ничего не делал; вместе с ним умерли pendingImages и
// #global-spinner, которых тоже нет в разметке. Оставлять этот код после переезда каналов
// было бы хуже, чем починить: он выглядел бы рабочим подписчиком нового адреса.
function updateChatHeaderAvatar(msg) {
    const message = JSON.parse(msg.body);
    const target = document.getElementById('chat-header-avatar');
    // STOMP-событие картинки несёт objectKey (см. контракт Images-топика).
    if (target && message.objectKey && message.objectKey !== 'pending') {
        target.innerHTML = policy.createHTML(avatarHtml(message.objectKey));
        hydrateImages(target);
    }
}

// --- AI assist (CSRF-заголовок axios добавит сам из XSRF-TOKEN cookie) ---
function requestAIResponse() {
    const selectedUser = document.getElementById('aiUserSelect').value;
    if (!selectedUser) {
        showToast('Пожалуйста, выберите пользователя', 'error');
        return;
    }
    // Хендлер ждёт multipart с полями TargetUsername/chat_id (@RequestPart)
    const formBody = new FormData();
    formBody.append('TargetUsername', selectedUser);
    formBody.append('chat_id', chat_id);

    const thinkingToast = showToast("AI думает...", 'info', 0);

    api.post('/AiAssist', formBody)
        .then(response => {
            thinkingToast.remove();
            if (response.data) {
                showAISuggestion(response.data);
            } else {
                showToast('AI не смог сгенерировать ответ', 'error');
            }
        })
        .catch(err => {
            thinkingToast.remove();
            console.error('Ошибка при запросе к AI:', err);
            showToast('Ошибка при запросе к AI', 'error');
        });
}

function showAISuggestion(text) {
    const panel = document.getElementById('aiSuggestionPanel');
    const messageInput = document.getElementById('messageInput');
    panel.innerHTML = policy.createHTML(`
        <span class="suggestion-text">${text}</span>
        <div class="suggestion-actions">
            <button class="insert-btn">Вставить</button>
            <button class="close-btn">Закрыть</button>
        </div>
    `);
    panel.querySelector('.insert-btn').addEventListener('click', () => {
        messageInput.value = text;
        hideAISuggestion();
        messageInput.focus();
    });
    panel.querySelector('.close-btn').addEventListener('click', hideAISuggestion);
    panel.style.display = 'flex';
}

function hideAISuggestion() {
    const panel = document.getElementById('aiSuggestionPanel');
    panel.style.display = 'none';
    panel.innerHTML = '';
}

function showToast(message, type = 'info', duration = 3000) {
    const container = document.getElementById('toastContainer');
    if (!container) return null;
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;
    toast.textContent = message;
    container.appendChild(toast);
    if (duration > 0) {
        setTimeout(() => toast.remove(), duration);
    }
    return toast;
}

// --- STOMP ---

// Таблица каналов (beads 8r7): по destination из отбивки /private/{user_id} определяем,
// какой канал упал, и каким клиентом/колбэком его переподписать. prefix повторяет
// PER_CHAT_PREFIXES на сервере (StompAuthChannelInterceptor) — те же три семейства,
// синхронизировать вручную. getClient — функция, а не прямая ссылка на переменную: клиенты
// присваиваются позже, в connectStomp(), и на момент объявления CHANNELS ещё не существуют.
const CHANNELS = {
    chat: {
        prefix: '/mutual/chat/',
        getClient: () => stompClient,
        onMessage: (msg) => appendChatMessage(JSON.parse(msg.body)),
    },
    typing: {
        prefix: '/mutual/typing/',
        getClient: () => statusStomp,
        onMessage: (message) => {
            const data = JSON.parse(message.body);
            if (String(data.user_id) === String(user_id)) return;
            if (data.status === "START") {
                showTypingIndicator(data.user_name);
            } else if (data.status === "STOP") {
                hideTypingIndicator(data.user_name);
            }
        },
    },
    chat_image: {
        prefix: '/mutual/chat_image/',
        getClient: () => imagesStomp,
        onMessage: updateChatHeaderAvatar,
    },
};

function destinationFor(name) {
    return `${CHANNELS[name].prefix}${chat_id}`;
}

// Приводит все три канала в состояние «заход в чат с нуля» (beads 8r7). Зовётся и из
// mount(), и из unmount() — см. комментарии там, причины разные.
function resetChannelState() {
    for (const state of Object.values(channelState)) {
        clearTimeout(state.retryTimer);
        state.retryTimer = null;
        state.subscription = null;
        state.retries = 0;
    }
}

// К какому каналу относится отбивка (beads 8wh, F1 → обобщено в 8r7). Совпадение строго по
// полному адресу ТЕКУЩЕГО чата — так же, как раньше сравнивался только `/mutual/chat/`.
function channelForDestination(destination) {
    if (!destination) return null;
    for (const name of Object.keys(CHANNELS)) {
        if (destination === destinationFor(name)) return name;
    }
    return null;
}

// Идемпотентная (пере)подписка на канал (beads 8wh, F1 → обобщено в 8r7). Вынесена в
// функцию, потому что её нужно уметь повторять: сервер может уронить SUBSCRIBE, не сумев
// проверить членство, и тогда единственный способ восстановиться без перезагрузки страницы —
// подписаться заново.
function subscribeChannel(name) {
    const channel = CHANNELS[name];
    const state = channelState[name];
    // Снимаем предыдущую подписку ПЕРЕД тем, как завести новую. Без этого повторный вызов
    // (после SUBSCRIPTION_UNAVAILABLE) оставлял бы старую подписку живой —
    // SimpleBrokerMessageHandler шлёт копию сообщения на каждую подписку, и onMessage
    // срабатывал бы по нескольку раз на одно сообщение/событие.
    if (state.subscription) {
        try {
            state.subscription.unsubscribe();
        } catch (e) {
            // Сервер мог и не зарегистрировать эту подписку (например, прошлый SUBSCRIBE
            // сам был уронён интерцептором) — тогда unsubscribe() на ней no-op с точки
            // зрения сервера, но stomp.js может бросить на несуществующем id. Не фатально.
            console.warn(`[chat] unsubscribe от предыдущей подписки (${name}) не удался`, e);
        } finally {
            // Обнуляем даже при исключении (beads 8wh, R3): если бросил сам client.subscribe
            // ниже (клиент отвалился между проверкой connected и вызовом), присваивания
            // новой подписке не произойдёт, а без finally здесь осталась бы ссылка на УЖЕ
            // отписанный объект — следующий повтор снова звал бы на нём unsubscribe() и
            // снова логировал бы тот же warn. Не фатально, но шумит без пользы.
            state.subscription = null;
        }
    }
    state.subscription = channel.getClient().subscribe(destinationFor(name), channel.onMessage);
}

function connectStomp(token) {
    const authHeaders = { Authorization: `Bearer ${token}` };

    stompClient = stomp.add(Stomp.over(new SockJS("/ChatMessagesConn")));
    stompClient.connect(authHeaders, () => {
        // Персональный адрес отказов (beads isf) регистрируется ПЕРВЫМ (beads 8wh, R6):
        // обоснование не в порядке preSend — applyPreSend синхронен на потоке отправителя
        // (см. StompFrameTimestampInterceptor, beads 525), а вот РЕГИСТРАЦИЯ подписки в
        // DefaultSubscriptionRegistry уезжает в пул clientInboundChannel и ничем не
        // упорядочена с этим потоком. Запас другой: отбивка SUBSCRIPTION_UNAVAILABLE
        // рождается только после decideBlocking, то есть не раньше чем через ~2 с
        // блокирующего ожидания (см. membershipTimeout() в ChatMembershipService) — за это
        // время задача регистрации /private/{userId} из пула успевает отработать с огромным
        // запасом. Подписка идёт через тот же клиент, что и сообщения чата, чтобы её
        // снимал общий disconnectAll в unmount().
        stompClient.subscribe(`/private/${user_id}`, handleChatError);
        subscribeChannel('chat');
    });

    statusStomp = stomp.add(Stomp.over(new SockJS("/StatusUserConn")));
    statusStomp.connect(authHeaders, () => {
        subscribeChannel('typing');
    });

    imagesStomp = stomp.add(Stomp.over(new SockJS('/MutualImagesConn')));
    imagesStomp.connect(authHeaders, () => {
        // Адрес несёт chat_id (beads bwh). Раньше здесь были два ГЛОБАЛЬНЫХ канала —
        // /mutual/chat/image_chat_channel и /mutual/chat/image_message_channel, — по которым
        // прилетали события картинок всех чатов системы: chatId и ключи объектов MinIO
        // раздавались любому, кто подписался. Теперь адрес чат-скоупный, и SUBSCRIBE на него
        // проходит только для участника (StompAuthChannelInterceptor).
        // Второго канала не стало: у события userimage targetId — это userId, а не chatId,
        // привязать его к чату нечем, и оно уехало на пер-юзерный /mutual/user_image/{userId}.
        subscribeChannel('chat_image');
    });
}

// --- mount/unmount ---
export async function mount(params) {
    chat_id = params.id;
    chat_title = params.title || '';

    user_id = null;
    user_name = null;
    user_image = null;
    typingUsers = [];
    stompClient = null;
    statusStomp = null;
    imagesStomp = null;
    typingTimeout = null;
    pendingText = '';
    // channelState сбрасывается и здесь, а не только в unmount() (beads 8r7). mount()
    // защитно обнуляет всё остальное модульное состояние выше по той же причине: порядок
    // вызовов задаёт роутер, и на mount() без предшествующего unmount() (или на unmount(),
    // упавшем на releaseImages/disconnectAll до цикла сброса) чат унаследовал бы исчерпанный
    // лимит попыток и ссылку на подписку из уже разорванного соединения. Сброс в unmount()
    // остаётся: он гасит таймер, который иначе доживёт до следующего экрана и выстрелит там.
    resetChannelState();

    stomp = createStompRegistry();
    ac = new AbortController();

    const app = document.getElementById('app');
    app.innerHTML = policy.createHTML(CHAT_HTML);

    if (!chat_id) {
        navigate('/reactive/chatlist');
        return;
    }

    // Возврат явным маршрутом, а не history.back(): в чат попадают и по прямой ссылке с
    // ?id=..., и тогда в истории возвращаться некуда — пользователь ушёл бы с сайта. У
    // экрана чата единственный родитель — список чатов, туда и ведём.
    document.getElementById('back-btn').addEventListener('click', () => {
        navigate('/reactive/chatlist');
    }, { signal: ac.signal });

    document.getElementById('sendBtn').addEventListener('click', sendChatMessage, { signal: ac.signal });
    document.getElementById('aiBtn').addEventListener('click', requestAIResponse, { signal: ac.signal });
    document.getElementById('messageInput').addEventListener('keydown', (e) => {
        if (e.key === 'Enter') sendChatMessage();
    }, { signal: ac.signal });
    document.getElementById('messageInput').addEventListener('input', debounce(() => {
        sendTypingStatus("START");
        clearTimeout(typingTimeout);
        typingTimeout = setTimeout(() => sendTypingStatus("STOP"), TYPING_TIMER);
    }, 500), { signal: ac.signal });

    try {
        const me = (await api.get('/api/me')).data;
        user_id = String(me.userId);
        user_name = me.username;
        user_image = me.imageUrl;

        const data = (await api.get('/api/chat', { params: { id: chat_id, title: chat_title } })).data;
        renderHeader(data);
        renderMembers(data.members);
        for (const msg of data.messages || []) {
            appendChatMessage(msg);
        }

        const token = await ensureAccessToken();
        connectStomp(token);
    } catch (e) {
        console.error("chat bootstrap failed:", e);
        // Молча оставлять пустой чат нельзя. 403 (не участник чата либо чата нет)
        // выглядел неотличимо от чата без сообщений: заголовок пустой, история пустая,
        // а поле ввода и «Send» на месте и создают впечатление рабочего чата, хотя
        // отправка тоже не пройдёт. Так выглядела и любая другая осечка бутстрапа.
        // 401 сюда не доходит — интерцептор axios после неудачного refresh уводит
        // на /welcome сам.
        showToast(e?.response?.status === 403
            ? 'Нет доступа к этому чату'
            : 'Не удалось открыть чат', 'error', 4000);
        // Тосты живут в #toastContainer — он сосед #app, а не его потомок,
        // и переживает смену вью.
        await navigate('/reactive/chatlist');
    }
}

export function unmount() {
    // blob-URL живут до отзыва (beads gs2) — без этого вкладка копила бы их при каждом
    // переходе между чатами.
    releaseImages();
    if (stomp) stomp.disconnectAll();
    clearTimeout(typingTimeout);
    // Таймеры повтора подписки (beads 8wh → обобщено на все три канала в 8r7) гасим по
    // тому же образцу, что и typingTimeout: клиенты stompClient/statusStomp/imagesStomp не
    // обнуляются здесь (сброс — в mount(), см. комментарий там) и переживают unmount(),
    // поэтому без явного clearTimeout просроченный повтор при возврате в чат (или в другой
    // чат) проскочил бы guard в handleChatError и создал бы вторую подписку на тот же адрес.
    // Заодно обнуляем subscription и retries — по той же причине, по которой раньше
    // отдельно зануляли chatSubscription и subscribeRetries: stomp.disconnectAll() выше уже
    // закрыл соединения, но сами ссылки переживают unmount() как модульные переменные —
    // без сброса следующий mount() того же чата в той же вкладке унаследовал бы объект
    // подписки из прошлого (уже разорванного) соединения и/или исчерпанный лимит попыток.
    resetChannelState();
    if (ac) ac.abort();
    stomp = null;
    ac = null;
}
