import api from "/axios.js";
import { imageTag, hydrateImages, releaseImages } from "/image_loader.js";
import { ensureAccessToken } from "/auth.js";
import { navigate } from "/router.js";
import { createStompRegistry } from "/stomp-lifecycle.js";
import { formatMessageTimestamp } from "/timestamp_format.js";

// === SPA-вью списка чатов (beads 55/57/58) ===
// Данные приходят из /api/*, STOMP аутентифицируется access-токеном на CONNECT.
// Разметка (была в chats_list.html) рисуется сюда в mount() — сервер отдаёт только shell.

// Кнопка создания чата живёт в общей разметке, а не внутри пустого состояния (beads 0mv):
// до неё маршрут /reactive/createchat был зарегистрирован в app.js, но из UI на него не
// ссылалось ничто — свежезарегистрированный пользователь упирался в список без единого
// интерактивного элемента, и чат создавался только если набрать URL руками. Второй чат
// таким же образом не создать, поэтому кнопка нужна в обоих состояниях списка, а не только
// рядом с «У вас пока нет чатов».
const CHATLIST_HTML = `
    <div class="post-feed">
        <h2 style="text-align: center;">Ваши чаты</h2>
        <button type="button" id="create-chat-btn" class="btn btn-regular">+ Новый чат</button>
        <p id="no-chats-message" style="text-align: center; color: #888; display: none;">У вас пока нет чатов</p>
        <div id="chatlist-spinner" style="text-align: center; color: #888;">Загрузка…</div>
        <div class="chat-messages" id="chats"></div>
    </div>
`;

let user_id = null;
let originalPreviews = {};
let typingUsers = new Map();
let stomp = null;

function chatCard({ chat_id, chat_title, chat_lastmessagetime, chat_preview, chat_preview_username, image_url }) {
    const chatPart = document.createElement('div');
    chatPart.className = 'post';
    chatPart.style.cursor = 'pointer';
    chatPart.dataset.chatId = chat_id;
    chatPart.dataset.chatTitle = chat_title;
    chatPart.onclick = () => navigate(`/reactive/chat?id=${chat_id}&title=${encodeURIComponent(chat_title || '')}`);

    // Превью и имя автора — пустые узлы, содержимое проставляется textContent ниже.
    // Тот же текст, приезжающий по STOMP, уже кладётся через textContent, так что при
    // интерполяции в HTML одна и та же строка рендерилась по-разному в зависимости от
    // того, пришла она с загрузкой списка или обновлением на лету.
    const hasPreview = chat_preview && chat_preview.trim() !== '';
    const previewBlock = hasPreview
        ? `<div class="post-content" style="display: flex; align-items: center; gap: 8px;">
                ${chat_preview_username ? `<span class="user-id" id="username-${chat_id}"></span>` : ''}
                <p id="preview-${chat_id}"></p>
           </div>`
        : `<div class="post-content" style="display: flex; align-items: center; gap: 8px;">
                 <p class="no-message" id="preview-${chat_id}">Нет сообщений</p>
           </div>`;

    // image_url — MinIO objectKey. Разметка отдаёт заглушку с меткой data-image-key, байты
    // подставляет hydrateImages через axios (beads gs2): тег <img> не умеет послать
    // Authorization, и такой запрос отбивался 401 ещё на ingress.
    const avatarImg = imageTag(image_url, 'chat-avatar', 'chat avatar');

    chatPart.innerHTML = policy.createHTML(`
        <div class="post-header">
            <div class="chat-avatar-container" id="chat-img-${chat_id}" data-chat-id="${chat_id}">
                ${image_url === 'pending' ? `<div class="spinner-avatar"></div>` : avatarImg}
            </div>
            <div class="user-info">
                <span class="user-name chat-title"></span>
                <span class="user-name">Чат №<span>${chat_id}</span></span>
            </div>
            <span class="post-time" id="timestamp-${chat_id}">${formatMessageTimestamp(chat_lastmessagetime)}</span>
        </div>
        ${previewBlock}
    `);

    chatPart.querySelector('.chat-title').textContent = chat_title || '';
    if (hasPreview) {
        chatPart.querySelector(`#preview-${CSS.escape(String(chat_id))}`).textContent = chat_preview;
        const authorSpan = chatPart.querySelector(`#username-${CSS.escape(String(chat_id))}`);
        if (authorSpan) authorSpan.textContent = `${chat_preview_username} : `;
    }
    return chatPart;
}

function appendChat(chat) {
    const chatsDiv = document.getElementById('chats');
    const noChatsMessage = document.getElementById('no-chats-message');
    if (noChatsMessage) noChatsMessage.style.display = 'none';
    const card = chatCard(chat);
    chatsDiv.prepend(card);
    hydrateImages(card);
}

function renderInitialChats(chats) {
    const chatsDiv = document.getElementById('chats');
    const noChatsMessage = document.getElementById('no-chats-message');
    document.getElementById('chatlist-spinner')?.remove();

    if (!chats || chats.length === 0) {
        if (noChatsMessage) noChatsMessage.style.display = 'block';
        return;
    }
    for (const chat of chats) {
        const card = chatCard({
            chat_id: chat.id,
            chat_title: chat.title,
            chat_lastmessagetime: chat.lastMessageTime,
            chat_preview: chat.preview,
            chat_preview_username: chat.preview_username,
            image_url: chat.chat_image_url
        });
        chatsDiv.appendChild(card);
        hydrateImages(card);
        originalPreviews[chat.id] = chat.preview || '';
    }
}

// --- typing-индикаторы ---
function showTypingIndicator(chatId, userName) {
    if (!typingUsers.has(chatId)) typingUsers.set(chatId, new Set());
    typingUsers.get(chatId).add(userName);
    renderTyping(chatId);
}

function hideTypingIndicator(chatId, userName) {
    if (!typingUsers.has(chatId)) return;
    const usersSet = typingUsers.get(chatId);
    usersSet.delete(userName);
    if (usersSet.size === 0) typingUsers.delete(chatId);
    renderTyping(chatId);
}

function renderTyping(chatId) {
    const previewElement = document.getElementById('preview-' + chatId);
    if (!previewElement) return;
    const usersSet = typingUsers.get(chatId);
    if (!usersSet || usersSet.size === 0) {
        previewElement.textContent = originalPreviews[chatId] || '';
        return;
    }
    const names = Array.from(usersSet);
    previewElement.textContent = names.length === 1
        ? `${names[0]} печатает...`
        : `${names.join(', ')} печатают...`;
}

// --- Картинки чатов: живое событие + догон пропущенного ---

/**
 * Подставляет картинку в плитку чата вместо спиннера или прежнего изображения.
 *
 * onlyIfPending разводит два вызова с разными правилами. Живое STOMP-событие меняет
 * плитку всегда: картинку чата могут заменить и потом, когда на месте спиннера уже
 * висит прежнее изображение. Опрос-догон, наоборот, трогает только спиннеры — иначе
 * каждая попытка пересоздавала бы blob-URL уже отрисованным плиткам, а отзываются
 * они лишь в unmount (beads gs2), то есть вкладка копила бы их на ровном месте.
 */
function applyChatImage(chatId, objectKey, { onlyIfPending } = {}) {
    // Ключ 'pending' означает «байты ещё едут в MinIO» — рисовать по нему нечего.
    if (!objectKey || objectKey === 'pending') return;
    const target = document.getElementById(`chat-img-${chatId}`);
    if (!target) return;
    if (onlyIfPending && !target.querySelector('.spinner-avatar')) return;
    target.innerHTML = policy.createHTML(imageTag(objectKey, 'chat-avatar', 'chat'));
    hydrateImages(target);
}

// Догон картинок, потерянных мимо STOMP (beads 1e2).
//
// Подписка на /mutual/chatlist/image/{id} заводится только при монтировании списка
// чатов, а создатель чата попадает сюда уже ПОСЛЕ того, как событие о его картинке
// опубликовано. STOMP не переигрывает сообщения: кадр, отправленный до SUBSCRIBE,
// потерян навсегда, и плитка автора крутила бы спиннер до ручной перезагрузки.
// Остальных участников это не задевает — они на списке уже сидят и событие получают
// живьём (проверено на стенде: картинка меняется без перезагрузки).
//
// Поэтому спиннеры добираем опросом. Паузы растут, попытки кончаются: застрявший
// 'pending' (событие в Kafka потеряно, консюмер лежит) не должен превращаться в
// бесконечный поток запросов к /api/chatlist с каждой открытой вкладки.
const PENDING_RETRY_DELAYS_MS = [1000, 2000, 4000, 8000];
let pendingRetryTimer = null;

function schedulePendingImageRefresh(attempt = 0) {
    if (attempt >= PENDING_RETRY_DELAYS_MS.length) return;
    // Ни одного спиннера — догонять нечего, дальше не планируем.
    if (!document.querySelector('.spinner-avatar')) return;

    pendingRetryTimer = setTimeout(async () => {
        pendingRetryTimer = null;
        try {
            const chats = (await api.get('/api/chatlist')).data || [];
            for (const chat of chats) {
                applyChatImage(chat.id, chat.chat_image_url, { onlyIfPending: true });
            }
        } catch (e) {
            // Не обрываем цепочку: разовый сбой сети не повод бросать оставшиеся попытки.
            console.error("pending image refresh failed:", e);
        }
        schedulePendingImageRefresh(attempt + 1);
    }, PENDING_RETRY_DELAYS_MS[attempt]);
}

// --- STOMP: все подключения с Authorization в CONNECT-заголовках ---
// Каждый клиент регистрируется в stomp-registry вью — unmount() гасит их разом.
function connectStomp(token) {
    const authHeaders = { Authorization: `Bearer ${token}` };

    // Одно соединение на все четыре подписки (было четыре, по одному на подписку).
    //
    // Спецификация WebSocket запрещает браузеру держать больше одного соединения в состоянии
    // CONNECTING к одному host:port, поэтому хендшейки к нашему хосту идут строго в очередь, и
    // холодный старт стоит N × время_одного_хендшейка. Замер на стенде: один сокет — 2.0 с,
    // четыре параллельно — 1.5 / 3.1 / 4.7 / 6.1 с, то есть ровно очередь. В консоли это
    // выглядело как 8 секунд до появления реалтайма.
    //
    // Четыре эндпоинта разделения не давали: StompConfig регистрирует все шесть адресов ОДНИМ
    // циклом с одинаковой конфигурацией, брокер один, а доступ разграничивает
    // StompAuthChannelInterceptor по destination подписки, а не по эндпоинту. То есть цена
    // платилась за различие, которого нет.
    //
    // Обратная сторона: теперь все четыре подписки живут на одном транспорте и умирают вместе.
    // Практической разницы нет — соединения к одному хосту и раньше отваливались разом; зато
    // гасить при уходе с экрана надо один сокет, а не четыре (а именно незакрытые сокеты и
    // плодили дублирующие подписки).
    const chatlistStomp = stomp.add(Stomp.over(new SockJS('/GeneralChatDataUpdateConn')));
    chatlistStomp.connect(authHeaders, () => {
        chatlistStomp.subscribe(`/mutual/chatlist/change_chatpreview/${user_id}`, (msg) => {
            const data = JSON.parse(msg.body);
            const previewElement = document.getElementById('preview-' + data.chat_id);
            const usernameElement = document.getElementById('username-' + data.chat_id);
            const timestampElement = document.getElementById('timestamp-' + data.chat_id);
            const imageContainer = document.getElementById('chat-img-' + data.chat_id);

            if (previewElement) previewElement.textContent = data.text;
            originalPreviews[data.chat_id] = data.text || '';
            if (usernameElement) usernameElement.textContent = data.username ? (data.username + ' : ') : '';
            if (timestampElement) timestampElement.textContent = formatMessageTimestamp(data.timestamp);
            if (imageContainer && data.image_url && data.image_url !== 'pending') {
                imageContainer.innerHTML = policy.createHTML(
                    imageTag(data.image_url, 'chat-avatar', 'chat avatar'));
                hydrateImages(imageContainer);
            }
        });

        chatlistStomp.subscribe(`/mutual/chatlist/list_update/${user_id}`, (msg) => {
            const data = JSON.parse(msg.body);
            appendChat({
                chat_id: data.chat_id,
                chat_title: data.title,
                chat_lastmessagetime: data.timestamp,
                chat_preview: data.text,
                chat_preview_username: data.username,
                image_url: data.image_url
            });
        });

        // Пер-юзерный адрес (beads bwh). Раньше здесь был глобальный
        // /mutual/chat_list/image_chat_channel — заметьте chat_list через подчёркивание:
        // он не подходил ни под один префикс интерцептора и потому пропускался
        // allow-by-default, раздавая chatId и ключи MinIO всех чатов системы.
        // Теперь сервер веером раскладывает событие по участникам чата, как typing-статусы,
        // а список чатов слушает один свой адрес — плитки появляются динамически, и
        // подписываться на каждый чат отдельно пришлось бы по мере их добавления.
        chatlistStomp.subscribe(`/mutual/chatlist/image/${user_id}`, (msg) => {
            const message = JSON.parse(msg.body);
            // STOMP-событие картинки несёт objectKey (см. контракт Images-топика).
            applyChatImage(message.targetId, message.objectKey);
        });

        chatlistStomp.subscribe(`/mutual/chatlist/typing/${user_id}`, (message) => {
            const data = JSON.parse(message.body);
            if (String(data.user_id) === String(user_id)) return;
            if (data.status === "START") {
                showTypingIndicator(data.chat_id, data.user_name);
            } else if (data.status === "STOP") {
                hideTypingIndicator(data.chat_id, data.user_name);
            }
        });
    });
}

// --- SPA-контракт: mount сбрасывает состояние вью, unmount гасит STOMP ---
export async function mount(params) {
    user_id = null;
    originalPreviews = {};
    typingUsers = new Map();
    stomp = createStompRegistry();

    const app = document.getElementById('app');
    if (app) app.innerHTML = policy.createHTML(CHATLIST_HTML);

    // Обработчик вешается ДО try — иначе падение бутстрапа (/api/me, /api/chatlist или
    // ensureAccessToken) снова оставляло бы пользователя на странице без единого действия,
    // то есть ровно в том тупике, который чинит beads 0mv. Переход через router.navigate,
    // а не href: фронт — частичный SPA (beads j35), полная перезагрузка здесь не нужна.
    // Слушатель не снимаем в unmount: он висит свойством onclick на узле, который живёт
    // внутри #app и целиком заменяется следующим innerHTML — как onclick у карточек чата.
    const createChatBtn = document.getElementById('create-chat-btn');
    if (createChatBtn) createChatBtn.onclick = () => navigate('/reactive/createchat');

    try {
        // Токен берём ПЕРВЫМ, до запросов за данными, и запросы пускаем параллельно.
        //
        // Раньше порядок был обратный: /api/me -> /api/chatlist -> ensureAccessToken(). На
        // свежей вкладке токена в памяти нет (inmemory.js), поэтому /api/me уходил заведомо
        // без Authorization, заведомо получал 401 и будил refresh через интерцептор — лишний
        // круг только чтобы добыть токен, который дальше по коду всё равно запрашивался явно.
        // При замеренных на стенде ~500 мс на round-trip это полсекунды на ровном месте, и
        // ещё столько же — на последовательности /api/me -> /api/chatlist, которые друг от
        // друга не зависят.
        //
        // Параллелить их безопасно ТОЛЬКО с токеном впереди: без него оба ушли бы без
        // Authorization и получили 401 одновременно, а это ровно та гонка обновления, от
        // которой сервер сносит сессию как при краже токена (см. auth.js).
        // Отказ обмена обрабатываем отдельно: раньше сюда приводил 401 на /api/me, и на
        // /welcome уводил интерцептор axios. Теперь токен запрашивается ДО запросов за
        // данными, интерцептор в этой ветке не участвует — увести должны мы, иначе аноним
        // остался бы на пустом экране. Клиентским переходом, как в createchat.view.js:
        // /welcome — роут того же shell'а, полная перезагрузка не нужна.
        let token;
        try {
            token = await ensureAccessToken();
        } catch (e) {
            console.error("chatlist bootstrap failed: нет живой сессии", e);
            await navigate('/welcome');
            return;
        }

        const [me, chats] = await Promise.all([
            api.get('/api/me').then(r => r.data),
            api.get('/api/chatlist').then(r => r.data),
        ]);
        user_id = String(me.userId);
        renderInitialChats(chats);

        connectStomp(token);

        // Подписка встала только сейчас — картинки, событие о которых ушло раньше,
        // придётся добрать опросом (см. schedulePendingImageRefresh).
        schedulePendingImageRefresh();
    } catch (e) {
        // 401 после refresh-retry интерцептор уже уводит на /welcome
        console.error("chatlist bootstrap failed:", e);
    }
}

export function unmount() {
    // Таймер догона снимаем раньше остального: иначе уже отмонтированная вью сходила бы
    // в /api/chatlist и полезла бы в узлы, которых в DOM больше нет.
    if (pendingRetryTimer) {
        clearTimeout(pendingRetryTimer);
        pendingRetryTimer = null;
    }
    // blob-URL живут до отзыва (beads gs2) — иначе вкладка копит их при каждом переходе.
    releaseImages();
    if (stomp) stomp.disconnectAll();
    stomp = null;
}
