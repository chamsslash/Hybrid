const user_id = document.getElementById('context').dataset.userId;

// === GeneralChatDataUpdateConn ===
const generalSocket = new SockJS('/GeneralChatDataUpdateConn?user_id=' + user_id);
const generalStomp = Stomp.over(generalSocket);

generalStomp.connect({}, () => {
    console.log('✅ STOMP: GeneralChatDataUpdateConn подключен');
    generalStomp.subscribe(`/mutual/chatlist/change_chatpreview/${user_id}`, (msg) => {
        const data = JSON.parse(msg.body);
        const chatId = data.chat_id;
        const previewText = data.text;
        const previewUsername = data.username;
        const timestamp = data.timestamp;
        const chat_image = data.image_url;

        const previewElement = document.getElementById('preview-' + chatId);
        const usernameElement = document.getElementById('username-' + chatId);
        const timestampElement = document.getElementById('timestamp-' + chatId);
        const imageContainer = document.getElementById('chat_img-' + chatId);

        if (previewElement) previewElement.textContent = previewText;
        if (usernameElement) usernameElement.textContent = previewUsername ? (previewUsername + ' : ') : '';
        if (timestampElement) timestampElement.textContent = timestamp;
        if (imageContainer && chat_image && chat_image !== 'pending') {
            imageContainer.innerHTML = policy.createHTML(`
                <img src="https://drive.google.com/thumbnail?id=${chat_image}&sz=w100"
                     alt="chat avatar" class="chat-avatar">`);}
    });
});


const listSocket = new SockJS('/MutualChatListNotificationConn?user_id=' + user_id);
const listStomp = Stomp.over(listSocket);

listStomp.connect({}, () => {
    console.log('✅ STOMP: MutualChatListNotificationConn подключен');
    listStomp.subscribe(`/private/chatlist/notify/${user_id}`, (msg) => {
        try {
            const data = JSON.parse(msg.body);
            showNotification(data.text, data.type);
        } catch (e) {
            console.error("Ошибка при парсинге уведомления:", e);
        }
    });
});


const changesSocket = new SockJS('/ChatChangesHandleConn?user_id=' + user_id);
const changesStomp = Stomp.over(changesSocket);

changesStomp.connect({}, () => {
    console.log('✅ STOMP: ChatChangesHandleConn подключен');
    changesStomp.subscribe(`/mutual/chatlist/list_update/${user_id}`, (msg) => {
        const data = JSON.parse(msg.body);
        const chatId = data.chat_id;
        const previewText = data.text;
        const previewUsername = data.username;
        const timestamp = data.timestamp;
        const chat_title = data.title;
        const image_url =data.image_url;

        appendChat({
            chat_id: chatId,
            chat_title: chat_title,
            chat_lastmessagetime: timestamp,
            chat_preview: previewText,
            chat_preview_username: previewUsername,
            image_url: image_url
        });
    });
});


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

function appendChat({ chat_id, chat_title, chat_lastmessagetime, chat_preview, chat_preview_username, image_url }) {
    const chatsDiv = document.getElementById('chats');
    const noChatsMessage = document.getElementById('no-chats-message');

    if (noChatsMessage) {
        noChatsMessage.style.display = 'none';
    }
    const chatPart = document.createElement('div');
    chatPart.className = 'post';
    chatPart.style.cursor = 'pointer';
    chatPart.dataset.chatId = chat_id;
    chatPart.dataset.chatTitle = chat_title;
    chatPart.onclick = () => window.location.href = `/chat?id=${chat_id}&title=${chat_title}`;

    const hasPreview = chat_preview && chat_preview.trim() !== '';
    const previewBlock = hasPreview
        ? `<div class="post-content" style="display: flex; align-items: center; gap: 8px;">
                ${chat_preview_username ? `<span class="user-id">${chat_preview_username} : </span>` : ''}
                <p>${chat_preview}</p>
           </div>`
        : `<div class="post-content" style="display: flex; align-items: center; gap: 8px;">
                 <p class="no-message" th:id="'preview-' + ${chat.id}">Нет сообщений</p>
           </div>`;

    const avatarImg = image_url && image_url !== 'pending'
        ? `<img src="https://drive.google.com/thumbnail?id=${image_url}&sz=w100" alt="chat avatar" class="chat-avatar">`
        : `<img src="/images/rofl-cat.jpg" alt="chat avatar" class="chat-avatar">`;

    chatPart.innerHTML = policy.createHTML( `
        <div class="post-header">
            <div class="chat-avatar-container" id="chat-img-${chat_id}" data-user-id="${chat_id}">
                ${image_url === 'pending' ? `<div class="spinner-avatar"></div>` : avatarImg}
            </div>
            <div class="user-info">
                <span class="user-name">${chat_title}</span>
                <span class="user-name">Чат №<span>${chat_id}</span></span>
            </div>
            <span class="post-time" id="timestamp-${chat_id}">${chat_lastmessagetime || ''}</span>
        </div>
        ${previewBlock}
    `);

    chatsDiv.prepend(chatPart);
    console.log(`✅ Чат №${chat_id} добавлен в список!`);
}
const socketForImages = new SockJS('/MutualImagesConn');
const stompClientimages = Stomp.over(socketForImages);

stompClientimages.connect({}, function (frame) {
    console.log('Connected: ' + frame);

    stompClientimages.subscribe(`/mutual/chat_list/image_chat_channel`, (msg) => {
        const message = JSON.parse(msg.body);

        const target = document.getElementById(`chat-img-${message.targetId}`);

        const imageUrl = message.image_url;
        if (target && imageUrl && imageUrl !== 'pending') {
            target.innerHTML = policy.createHTML(`
        <img src="https://drive.google.com/thumbnail?id=${imageUrl}&sz=w100"
             class="chat-avatar" alt="chat">`);
        }
    });
});
const originalPreviews = {};

// Сохраняем оригинальные превью при загрузке страницы
window.addEventListener('DOMContentLoaded', () => {
    document.querySelectorAll('[id^="preview-"]').forEach(el => {
        const chatId = el.id.replace('preview-', '');
        originalPreviews[chatId] = el.textContent;
    });
});
const statusSocket = new SockJS("/StatusUserConn");
const StatusStomp = Stomp.over(statusSocket);
const  typingUsers =  new Map;
StatusStomp.connect({}, () => {
    StatusStomp.subscribe("/mutual/typing_statuses_channel", (message) => {
        const data = JSON.parse(message.body);
        const typingUserId = data.user_id;
        const username = data.user_name;
        const status = data.status;
        const chat_id = data.chat_id;

        if (typingUserId === user_id){
            console.log("Пропускаем, так как это я сам — не отображаем.");
            return;
        }


        if (status === "START") {
            showTypingIndicator(chat_id, username);
        } else if (status === "STOP") {
            hideTypingIndicator(chat_id, username);
        }
    });
});

function showTypingIndicator(chatId, userName) {
    if (!typingUsers.has(chatId)) {
        typingUsers.set(chatId, new Set());
    }
    typingUsers.get(chatId).add(userName);
    renderTyping(chatId);
}

function hideTypingIndicator(chatId, userName) {
    if (!typingUsers.has(chatId)) return;

    const usersSet = typingUsers.get(chatId);
    usersSet.delete(userName);

    if (usersSet.size === 0) {
        typingUsers.delete(chatId);
    }
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
    if (names.length === 1) {
        previewElement.textContent = `${names[0]} печатает...`;
    } else {
        previewElement.textContent = `${names.join(', ')} печатают...`;
    }
}

