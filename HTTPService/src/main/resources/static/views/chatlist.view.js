import api from "/axios.js";
import { ensureAccessToken } from "/auth.js";
import { navigate } from "/router.js";
import { createStompRegistry } from "/stomp-lifecycle.js";
import { formatMessageTimestamp } from "/timestamp_format.js";

// === SPA-вью списка чатов (beads 55/57/58) ===
// Данные приходят из /api/*, STOMP аутентифицируется access-токеном на CONNECT.
// Разметка (была в chats_list.html) рисуется сюда в mount() — сервер отдаёт только shell.

const CHATLIST_HTML = `
    <div class="post-feed">
        <h2 style="text-align: center;">Ваши чаты</h2>
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

    const hasPreview = chat_preview && chat_preview.trim() !== '';
    const previewBlock = hasPreview
        ? `<div class="post-content" style="display: flex; align-items: center; gap: 8px;">
                ${chat_preview_username ? `<span class="user-id" id="username-${chat_id}">${chat_preview_username} : </span>` : ''}
                <p id="preview-${chat_id}">${chat_preview}</p>
           </div>`
        : `<div class="post-content" style="display: flex; align-items: center; gap: 8px;">
                 <p class="no-message" id="preview-${chat_id}">Нет сообщений</p>
           </div>`;

    // image_url — MinIO objectKey; байты отдаёт GET /api/images/{key} (слэши не кодируем).
    const avatarImg = image_url && image_url !== 'pending'
        ? `<img src="/api/images/${image_url}" alt="chat avatar" class="chat-avatar">`
        : `<img src="/images/rofl-cat.jpg" alt="chat avatar" class="chat-avatar">`;

    chatPart.innerHTML = policy.createHTML(`
        <div class="post-header">
            <div class="chat-avatar-container" id="chat-img-${chat_id}" data-chat-id="${chat_id}">
                ${image_url === 'pending' ? `<div class="spinner-avatar"></div>` : avatarImg}
            </div>
            <div class="user-info">
                <span class="user-name">${chat_title || ''}</span>
                <span class="user-name">Чат №<span>${chat_id}</span></span>
            </div>
            <span class="post-time" id="timestamp-${chat_id}">${formatMessageTimestamp(chat_lastmessagetime)}</span>
        </div>
        ${previewBlock}
    `);
    return chatPart;
}

function appendChat(chat) {
    const chatsDiv = document.getElementById('chats');
    const noChatsMessage = document.getElementById('no-chats-message');
    if (noChatsMessage) noChatsMessage.style.display = 'none';
    chatsDiv.prepend(chatCard(chat));
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
        chatsDiv.appendChild(chatCard({
            chat_id: chat.id,
            chat_title: chat.title,
            chat_lastmessagetime: chat.lastMessageTime,
            chat_preview: chat.preview,
            chat_preview_username: chat.preview_username,
            image_url: chat.chat_image_url
        }));
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

// --- STOMP: все подключения с Authorization в CONNECT-заголовках ---
// Каждый клиент регистрируется в stomp-registry вью — unmount() гасит их разом.
function connectStomp(token) {
    const authHeaders = { Authorization: `Bearer ${token}` };

    const generalStomp = stomp.add(Stomp.over(new SockJS('/GeneralChatDataUpdateConn')));
    generalStomp.connect(authHeaders, () => {
        generalStomp.subscribe(`/mutual/chatlist/change_chatpreview/${user_id}`, (msg) => {
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
                imageContainer.innerHTML = policy.createHTML(`
                    <img src="/api/images/${data.image_url}"
                         alt="chat avatar" class="chat-avatar">`);
            }
        });
    });

    const changesStomp = stomp.add(Stomp.over(new SockJS('/ChatChangesHandleConn')));
    changesStomp.connect(authHeaders, () => {
        changesStomp.subscribe(`/mutual/chatlist/list_update/${user_id}`, (msg) => {
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
    });

    const imagesStomp = stomp.add(Stomp.over(new SockJS('/MutualImagesConn')));
    imagesStomp.connect(authHeaders, () => {
        imagesStomp.subscribe(`/mutual/chat_list/image_chat_channel`, (msg) => {
            const message = JSON.parse(msg.body);
            const target = document.getElementById(`chat-img-${message.targetId}`);
            // STOMP-событие картинки несёт objectKey (см. контракт Images-топика).
            if (target && message.objectKey && message.objectKey !== 'pending') {
                target.innerHTML = policy.createHTML(`
                    <img src="/api/images/${message.objectKey}"
                         class="chat-avatar" alt="chat">`);
            }
        });
    });

    const statusStomp = stomp.add(Stomp.over(new SockJS("/StatusUserConn")));
    statusStomp.connect(authHeaders, () => {
        statusStomp.subscribe("/mutual/typing_statuses_channel", (message) => {
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

    try {
        const me = (await api.get('/api/me')).data;
        user_id = String(me.userId);

        const chats = (await api.get('/api/chatlist')).data;
        renderInitialChats(chats);

        const token = await ensureAccessToken();
        connectStomp(token);
    } catch (e) {
        // 401 после refresh-retry интерцептор уже уводит на /welcome
        console.error("chatlist bootstrap failed:", e);
    }
}

export function unmount() {
    if (stomp) stomp.disconnectAll();
    stomp = null;
}
