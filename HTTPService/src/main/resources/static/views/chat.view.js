import api from "/axios.js";
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
        <div class="chat-avatar-container" id="chat-header-avatar"></div>
        <span id="chat-title"></span>
        <div id="typing-indicator"></div>
    </div>

    <div class="chat-messages" id="chatMessages"></div>

    <div class="chat-input">
        <input type="text" id="messageInput" placeholder="Type your message...">
        <button id="sendBtn" type="button">Send</button>
        <button id="aiBtn" type="button">Помощь AI</button>
    </div>
    <div id="aiSuggestionPanel" class="ai-suggestion-panel" style="display: none;"></div>
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
let typingTimeout = null;
const pendingImages = new Set();

let stomp = null;
let ac = null;

// key — MinIO objectKey (напр. userimage/42/uuid.png); байты отдаёт GET /api/images/{key}.
// Эндпоинт принимает слэши как есть — кодировать ключ не нужно.
function avatarHtml(key) {
    return key && key !== 'pending'
        ? `<img src="/api/images/${key}" alt="chat avatar" class="chat-avatar">`
        : `<img src="/images/rofl-cat.jpg" alt="chat avatar" class="chat-avatar">`;
}

function appendChatMessage({ user_id: senderId, username, timestamp, text, imageurl, image_url }) {
    const container = document.getElementById('chatMessages');
    const msg = document.createElement('div');
    msg.classList.add('message');
    msg.classList.add(String(senderId) === String(user_id) ? 'user' : 'bot');

    const img = imageurl ?? image_url;
    msg.innerHTML = policy.createHTML(`
        <div>
            <div class="message-avatar">${avatarHtml(img)}</div>
            <strong>${username || 'anon'}</strong>
            <strong>ID: ${senderId || 'anon'}</strong>
            <span style="float: right; font-size: 12px; color: #999;">
                ${formatMessageTimestamp(timestamp)}
            </span>
        </div>
        <div><span>${text}</span></div>
    `);

    container.appendChild(msg);
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
    input.value = "";
}

function updateImage(msg, prefix) {
    const message = JSON.parse(msg.body);
    const target = document.getElementById(`${prefix}${message.targetId}`);
    // STOMP-событие картинки несёт objectKey (см. контракт Images-топика).
    if (target && message.objectKey && message.objectKey !== 'pending') {
        target.innerHTML = policy.createHTML(avatarHtml(message.objectKey));
        pendingImages.delete(String(message.targetId));
        if (pendingImages.size === 0) {
            const globalSpinner = document.getElementById('global-spinner');
            if (globalSpinner) globalSpinner.style.display = 'none';
        }
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
function connectStomp(token) {
    const authHeaders = { Authorization: `Bearer ${token}` };

    stompClient = stomp.add(Stomp.over(new SockJS("/ChatMessagesConn")));
    stompClient.connect(authHeaders, () => {
        stompClient.subscribe(`/mutual/chat/${chat_id}`, (msg) => {
            appendChatMessage(JSON.parse(msg.body));
        });
    });

    const statusStomp = stomp.add(Stomp.over(new SockJS("/StatusUserConn")));
    statusStomp.connect(authHeaders, () => {
        statusStomp.subscribe(`/mutual/typing/${chat_id}`, (message) => {
            const data = JSON.parse(message.body);
            if (String(data.user_id) === String(user_id)) return;
            if (data.status === "START") {
                showTypingIndicator(data.user_name);
            } else if (data.status === "STOP") {
                hideTypingIndicator(data.user_name);
            }
        });
    });

    const imagesStomp = stomp.add(Stomp.over(new SockJS('/MutualImagesConn')));
    imagesStomp.connect(authHeaders, () => {
        imagesStomp.subscribe(`/mutual/chat/image_chat_channel`, (msg) => updateImage(msg, 'chat_img-'));
        imagesStomp.subscribe(`/mutual/chat/image_message_channel`, (msg) => updateImage(msg, 'img-'));
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
    typingTimeout = null;
    pendingImages.clear();

    stomp = createStompRegistry();
    ac = new AbortController();

    const app = document.getElementById('app');
    app.innerHTML = policy.createHTML(CHAT_HTML);

    if (!chat_id) {
        navigate('/reactive/chatlist');
        return;
    }

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
    }
}

export function unmount() {
    if (stomp) stomp.disconnectAll();
    clearTimeout(typingTimeout);
    if (ac) ac.abort();
    stomp = null;
    ac = null;
}
