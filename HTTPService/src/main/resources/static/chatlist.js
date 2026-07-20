import api from "/axios.js";
import { ensureAccessToken } from "/auth.js";

// === SPA-шелл списка чатов (beads 55/57/58) ===
// Данные приходят из /api/*, STOMP аутентифицируется access-токеном на CONNECT.

let user_id = null;
const originalPreviews = {};
const typingUsers = new Map();

function showNotification(message, type = 'info') {
    const toastContainer = document.getElementById('toastContainer');
    const toast = document.createElement('div');
    toast.classList.add('toast', `toast-${type}`);

    const icons = { info: 'ℹ️', success: '✅', error: '❌', warning: '⚠️' };
    toast.innerHTML = policy.createHTML(`
            <span class="toast-icon">${icons[type] || 'ℹ️'}</span>
            <span class="toast-text">${message}</span>
        `);
    toastContainer.appendChild(toast);

    setTimeout(() => {
        toast.classList.add('hide');
        toast.addEventListener('transitionend', () => toast.remove());
    }, 5000);
}

function chatCard({ chat_id, chat_title, chat_lastmessagetime, chat_preview, chat_preview_username, image_url }) {
    const chatPart = document.createElement('div');
    chatPart.className = 'post';
    chatPart.style.cursor = 'pointer';
    chatPart.dataset.chatId = chat_id;
    chatPart.dataset.chatTitle = chat_title;
    chatPart.onclick = () => window.location.href = `/reactive/chat?id=${chat_id}&title=${encodeURIComponent(chat_title || '')}`;

    const hasPreview = chat_preview && chat_preview.trim() !== '';
    const previewBlock = hasPreview
        ? `<div class="post-content" style="display: flex; align-items: center; gap: 8px;">
                ${chat_preview_username ? `<span class="user-id" id="username-${chat_id}">${chat_preview_username} : </span>` : ''}
                <p id="preview-${chat_id}">${chat_preview}</p>
           </div>`
        : `<div class="post-content" style="display: flex; align-items: center; gap: 8px;">
                 <p class="no-message" id="preview-${chat_id}">Нет сообщений</p>
           </div>`;

    const avatarImg = image_url && image_url !== 'pending'
        ? `<img src="https://drive.google.com/thumbnail?id=${image_url}&sz=w100" alt="chat avatar" class="chat-avatar">`
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
            <span class="post-time" id="timestamp-${chat_id}">${chat_lastmessagetime || ''}</span>
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
function connectStomp(token) {
    const authHeaders = { Authorization: `Bearer ${token}` };

    const generalStomp = Stomp.over(new SockJS('/GeneralChatDataUpdateConn'));
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
            if (timestampElement) timestampElement.textContent = data.timestamp;
            if (imageContainer && data.image_url && data.image_url !== 'pending') {
                imageContainer.innerHTML = policy.createHTML(`
                    <img src="https://drive.google.com/thumbnail?id=${data.image_url}&sz=w100"
                         alt="chat avatar" class="chat-avatar">`);
            }
        });
    });

    const listStomp = Stomp.over(new SockJS('/MutualChatListNotificationConn'));
    listStomp.connect(authHeaders, () => {
        listStomp.subscribe(`/private/chatlist/notify/${user_id}`, (msg) => {
            try {
                const data = JSON.parse(msg.body);
                showNotification(data.text, data.type);
            } catch (e) {
                console.error("Ошибка при парсинге уведомления:", e);
            }
        });
    });

    const changesStomp = Stomp.over(new SockJS('/ChatChangesHandleConn'));
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

    const imagesStomp = Stomp.over(new SockJS('/MutualImagesConn'));
    imagesStomp.connect(authHeaders, () => {
        imagesStomp.subscribe(`/mutual/chat_list/image_chat_channel`, (msg) => {
            const message = JSON.parse(msg.body);
            const target = document.getElementById(`chat-img-${message.targetId}`);
            if (target && message.image_url && message.image_url !== 'pending') {
                target.innerHTML = policy.createHTML(`
                    <img src="https://drive.google.com/thumbnail?id=${message.image_url}&sz=w100"
                         class="chat-avatar" alt="chat">`);
            }
        });
    });

    const statusStomp = Stomp.over(new SockJS("/StatusUserConn"));
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

// --- bootstrap: identity → данные → STOMP ---
(async () => {
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
})();
