const csrfToken = document.querySelector('meta[name="_csrf"]').content;
const csrfHeader = document.querySelector('meta[name="_csrf_header"]').content;
console.log('csrfHeader:', csrfHeader);
console.log('csrfToken:', csrfToken);
const TYPING_TIMER = 1000;
const ctx = document.getElementById('context');
const user_id = ctx.dataset.userId;
const user_name = ctx.dataset.username;
const chat_id = ctx.dataset.chatId;
const user_image = ctx.dataset.userimage;
const input = document.getElementById("messageInput");

let typingTimeout;

const socket = new SockJS("/ChatMessagesConn");
const stompClient = Stomp.over(socket);
const currentUserId = user_id; // Текущий пользователь
const typingUsers = []; // просто массив с именами
function debounce(fn, delay) {
    let timer;
    return function(...args) {
        clearTimeout(timer);
        timer = setTimeout(() => fn.apply(this, args), delay);
    };
}
input.addEventListener('input', debounce(() => {
    sendTypingStart();

    clearTimeout(typingTimeout);
    typingTimeout = setTimeout(() => {
        sendTypingStop();
    }, TYPING_TIMER);
},500));

const statusSocket = new SockJS("/StatusUserConn");
const StatusStomp = Stomp.over(statusSocket);

StatusStomp.connect({}, () => {
    StatusStomp.subscribe("/mutual/typing_statuses_channel" + chat_id, (message) => {
        const data = JSON.parse(message.body);
        const typingUserId = data.user_id;
        const username = data.user_name;
        const status = data.status;

        if (typingUserId === currentUserId) return;

        if (status === "START") {
            showTypingIndicator(username);
        } else if (status === "STOP") {
            hideTypingIndicator(username);
        }
    });
});

function sendTypingStart() {
    stompClient.send("/app/chat/user_statuses", {}, JSON.stringify({
        user_id: currentUserId,
        status: "START",
        user_name: user_name,
        chat_id : chat_id
    }));
}

function sendTypingStop() {
    stompClient.send("/app/chat/user_statuses", {}, JSON.stringify({
        user_id: currentUserId,
        status: "STOP",
        user_name: user_name,
        chat_id : chat_id
    }));
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
    const names = typingUsers;
    if (names.length === 0) {
        indicator.textContent = '';
    } else if (names.length === 1) {
        indicator.textContent = `${names[0]} печатает...`;
    } else {
        indicator.textContent = `${names.join(', ')} печатают...`;
    }
}


stompClient.connect({}, () => {
    console.log("✅ STOMP подключен");

    stompClient.subscribe(`/mutual/chat/${chat_id}`, (msg) => {
        const message = JSON.parse(msg.body);
        appendChatMessage(message);
    });
});

const socketForImages = new SockJS('/MutualImagesConn');
const stompClientimages = Stomp.over(socketForImages);

stompClientimages.connect({}, () => {
    stompClientimages.subscribe(`/mutual/chat/image_chat_channel`, (msg) => {
        updateImage(msg, 'chat_img-');
    });

    stompClientimages.subscribe(`/mutual/chat/image_message_channel`, (msg) => {
        updateImage(msg, 'img-');
    });
});

const pendingImages = new Set();

window.addEventListener('DOMContentLoaded', () => {
    document.querySelectorAll('.spinner-avatar').forEach(spinner => {
        const parent = spinner.closest('[data-user-id]');
        if (parent) {
            const userId = parent.dataset.userId;
            if (userId) pendingImages.add(userId);
        }
    });

    if (pendingImages.size > 0) {
        const globalSpinner = document.getElementById('global-spinner');
        if(globalSpinner) {
            globalSpinner.style.display = 'flex';
        }
    }
});

function updateImage(msg, prefix) {
    const message = JSON.parse(msg.body);
    const target = document.getElementById(`${prefix}${message.targetId}`);
    if (target && message.image_url && message.image_url !== 'pending') {
        target.innerHTML = policy.createHTML(`
            <img src="https://drive.google.com/thumbnail?id=${message.image_url}&sz=w1000"
                 class="chat-avatar" alt="avatar">`);

        pendingImages.delete(String(message.targetId));

        if (pendingImages.size === 0) {
            const globalSpinner = document.getElementById('global-spinner');
            if(globalSpinner) {
                globalSpinner.style.display = 'none';
            }
        }
    }
}

function sendChatMessage() {
    const input = document.getElementById('messageInput');
    const text = input.value.trim();
    if (!text) return;
    const date = new Date();
    const options = { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' };
    const formatted = date.toLocaleString('ru-RU', options);
    console.log(formatted);
    const message = {
        username: user_name,
        chat_id: chat_id,
        timestamp: formatted,
        user_id: user_id,
        text: text,
        imageurl: user_image
    };

    stompClient.send(`/app/chat/send/${chat_id}`, {}, JSON.stringify(message));
    input.value = "";
}

function appendChatMessage({ user_id: senderId, username, timestamp, text, imageurl }) {
    const container = document.getElementById('chatMessages');
    const msg = document.createElement('div');
    msg.classList.add('message');
    msg.classList.add(senderId == user_id ? 'user' : 'bot');

    const avatarImg = imageurl
        ? `<img src="https://drive.google.com/thumbnail?id=${imageurl}&sz=w1000" alt="chat avatar" class="chat-avatar">`
        : `<img src="/images/rofl-cat.jpg" alt="chat avatar" class="chat-avatar">`;

    msg.innerHTML = policy.createHTML(`
        <div>
            <div class="message-avatar">${avatarImg}</div>
            <strong>${username || 'anon'}</strong>
            <strong>ID: ${senderId || 'anon'}</strong>
            <span style="float: right; font-size: 12px; color: #999;">
                ${timestamp || new Date().toISOString()}
            </span>
        </div>
        <div><span>${text}</span></div>
    `);

    container.appendChild(msg);
    container.scrollTop = container.scrollHeight;
}
function requestAIResponse() {
    const selectedUser = document.getElementById('aiUserSelect').value;
    if (!selectedUser) {
        showToast('Пожалуйста, выберите пользователя', 'error');
        return;
    }

    const formBody = new URLSearchParams();
    formBody.append('TargetUserName', selectedUser);
    formBody.append('chat_id', chat_id);

    // Показываем уведомление о загрузке и сохраняем его, чтобы скрыть позже
    const thinkingToast = showToast("AI думает...", 'info', 0); // 0 = не скрывать автоматически

    fetch('/Aiassist', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
            [csrfHeader]: csrfToken
        },
        body: formBody.toString()
    })
        .then(response => {
            if (!response.ok) {
                throw new Error('Сетевой ответ был не в порядке: ' + response.statusText);
            }
            return response.text();
        })
        .then(data => {
            // Успех! Первым делом прячем уведомление о загрузке.
            thinkingToast.remove();

            console.log('Ответ AI:', data);
            if (data) {
                // Показываем панель с результатом
                showAISuggestion(data);
            } else {
                showToast('AI не смог сгенерировать ответ', 'error');
            }
        })
        .catch(err => {
            // Ошибка! Тоже прячем уведомление о загрузке.
            thinkingToast.remove();

            console.error('Ошибка при запросе к AI:', err);
            showToast('Ошибка при запросе к AI', 'error');
        });
}
function showAISuggestion(text) {
    const panel = document.getElementById('aiSuggestionPanel');
    const messageInput = document.getElementById('messageInput');

    // Наполняем панель контентом
    panel.innerHTML =policy.createHTML( `
        <span class="suggestion-text">${text}</span>
        <div class="suggestion-actions">
            <button class="insert-btn">Вставить</button>
            <button class="close-btn">Закрыть</button>
        </div>
    `);

    // Добавляем обработчики событий для НОВЫХ кнопок
    panel.querySelector('.insert-btn').addEventListener('click', () => {
        messageInput.value = text;
        hideAISuggestion();
        messageInput.focus();
    });

    panel.querySelector('.close-btn').addEventListener('click', () => {
        hideAISuggestion();
    });

    // Показываем панель
    panel.style.display = 'flex';
}
function hideAISuggestion() {
    const panel = document.getElementById('aiSuggestionPanel');
    panel.style.display = 'none';
    panel.innerHTML = '';
}
function showToast(message, type = 'info', duration = 3000) {
    const container = document.getElementById('toastContainer');
    if (!container) {
        console.error('Toast container not found!');
        return;
    }
    const toast = document.createElement('div');

    toast.className = `toast ${type}`;
    toast.textContent = message;

    container.appendChild(toast);


    if (duration <= 0) {
        setTimeout(() => {
            toast.remove();
        }, duration);
    }


    return toast;
}