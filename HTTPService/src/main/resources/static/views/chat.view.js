import api from "/axios.js";
import { imageTag, hydrateImages, releaseImages } from "/image_loader.js";
import { ensureAccessToken } from "/auth.js";
import { navigate } from "/router.js";
import { createStompRegistry } from "/stomp-lifecycle.js";
import { showToast } from "/toast.js";
import { formatMessageTimestamp } from "/timestamp_format.js";

// === SPA-вью страницы чата (beads 55/57/58) ===
// id/title — из params роутера (mount(params)), данные — из /api/chat, STOMP — c access-токеном.

const TYPING_TIMER = 1000;

const CHAT_HTML = `
<div class="screen screen-wide chat-screen">
    <section class="chat-main">
        <div class="chat-header">
            <!-- Кнопка «назад» нужна на узких экранах: там рельс превращается в
                 нижнюю полосу и ведёт в разделы, а не на шаг назад к списку. -->
            <button type="button" id="back-btn" class="back-button" aria-label="Назад к списку чатов">←</button>

            <!-- Аватарка и название обёрнуты в кнопку: состав чата открывается кликом по шапке,
                 как в привычных мессенджерах. Кнопка, а не div с onclick, — чтобы работали
                 Tab и Enter и чтобы скринридер назвал элемент действием, а не текстом.
                 На широком экране состав и так виден в правой колонке, но кнопка остаётся:
                 колонку прячет media-query, а не отдельная ветка в JS. -->
            <button type="button" id="chat-info-btn" class="chat-info-button"
                    aria-haspopup="dialog" aria-label="Показать участников чата">
                <div class="chat-avatar-container" id="chat-header-avatar"></div>
                <span id="chat-title"></span>
            </button>

            <div id="typing-indicator"></div>

            <select id="aiUserSelect" aria-label="Пользователь для AI-ответа">
                <option value="">AI-ответ за…</option>
            </select>
        </div>

        <!-- Модалка состава. Лежит в разметке сразу, а не создаётся по клику: содержимое
             заполняется один раз при загрузке чата, открытие — это только снятие hidden.
             На узких экранах она единственный способ увидеть состав. -->
        <div id="members-overlay" class="modal-overlay" hidden>
            <div class="modal-card" role="dialog" aria-modal="true" aria-labelledby="members-title">
                <div class="modal-head">
                    <h3 id="members-title">Участники</h3>
                    <button type="button" id="members-close" class="modal-close" aria-label="Закрыть">✕</button>
                </div>
                <ul id="members-list" class="members-list"></ul>
            </div>
        </div>

        <div class="chat-messages" id="chatMessages"></div>

        <div class="chat-input">
            <!-- Скрепка слева от поля ввода: открывает панель стикеров (beads a22).
                 Скрытый file input живёт рядом, а не внутри панели, — панель
                 перерисовывается при смене вкладки, и input вместе с ней терял бы
                 выбранный файл. -->
            <button id="stickerBtn" type="button" class="attach-btn" aria-label="Стикеры"
                    aria-haspopup="true" aria-expanded="false">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
                     stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                    <path d="M21 11.5 12.5 20a5 5 0 0 1-7-7l8.5-8.5a3.5 3.5 0 0 1 5 5L10.5 18a2 2 0 0 1-3-3l8-8"/>
                </svg>
            </button>
            <input type="file" id="stickerFile" accept="image/png,image/jpeg,image/gif,image/webp" hidden>
            <input type="text" id="messageInput" placeholder="Напишите сообщение…">
            <button id="aiBtn" type="button" class="ghost">Помощь AI</button>
            <button id="sendBtn" type="button" class="send-btn" aria-label="Отправить">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"
                     stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                    <path d="M4.5 12.5 20 5l-7 15-2.2-6.3z"/>
                </svg>
            </button>
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

            <!-- Панель стикеров лежит ВНУТРИ .chat-input по той же причине, что и
                 #aiSuggestionPanel выше: её CSS — position: absolute; bottom: calc(100% + 8px) —
                 отсчитывается от строки ввода, у которой уже есть position: relative.
                 Соседом она отсчитывалась бы от body и уезжала за верхний край экрана. -->
            <div id="stickerPanel" class="sticker-panel" hidden>
                <div class="sticker-panel-head">
                    <div class="sticker-tabs" role="tablist">
                        <button type="button" class="sticker-tab is-active" data-tab="upload"
                                role="tab" aria-selected="true">Загрузить</button>
                        <button type="button" class="sticker-tab" data-tab="mine"
                                role="tab" aria-selected="false">Мои стикеры</button>
                    </div>
                    <button type="button" id="stickerClose" class="modal-close" aria-label="Закрыть">✕</button>
                </div>
                <div class="sticker-pane" data-pane="upload">
                    <button type="button" id="stickerPick" class="sticker-pick">Выбрать картинку</button>
                    <p class="sticker-hint">PNG, JPEG, GIF или WEBP. Картинка сразу уйдёт в этот чат
                        и попадёт в ваш набор.</p>
                </div>
                <div class="sticker-pane" data-pane="mine" hidden>
                    <div id="stickerGrid" class="sticker-grid"></div>
                </div>
            </div>
        </div>
    </section>

    <!-- Правая колонка — тот же состав, что в модалке, но постоянно на виду.
         Прячется media-query ниже 1100px. -->
    <aside class="chat-side">
        <div class="chat-side-head">
            <div class="chat-avatar-container" id="side-avatar"></div>
            <span class="chat-side-title" id="side-title"></span>
        </div>
        <h3 class="chat-side-caption" id="members-panel-title">Участники</h3>
        <ul id="members-panel-list" class="members-list"></ul>
    </aside>
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

// Текст последнего отправленного сообщения (beads isf). Поле ввода чистится сразу после
// send, потому что STOMP ничего не подтверждает; если сервер отказал (нас убрали из чата,
// MessegerParody недоступен), текст возвращается сюда из отбивки на /private/{user_id}.
// Корреляции «эхо подтвердило доставку → можно чистить» здесь нет намеренно: она требует
// сквозного идентификатора сообщения, которого в контракте нет.
let pendingText = '';

// Ретрай подписок при SUBSCRIPTION_UNAVAILABLE (изначально beads 8wh, обобщено на все три
// канала в 8r7). Сообщения/typing/аватарка чата едут по ОДНОМУ соединению (см. connectStomp),
// но сервер роняет их подписки НЕЗАВИСИМО друг от друга — решение принимается по destination
// (StompAuthChannelInterceptor.PER_CHAT_PREFIXES), а не по транспорту. Поэтому бюджет попыток
// и таймер повтора держим РАЗДЕЛЬНО на канал в channelState: общий счётчик означал бы, что
// один сбойный канал сжигает лимит попыток остальным.
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

// Номер поколения монтирования. Между `await` внутри mount() и работой с DOM/STOMP
// вью может быть уже снята: роутер чистит #app и зовёт unmount(), не дожидаясь, пока
// доедет бутстрап предыдущего экрана. Продолжать после этого нельзя по двум причинам.
//
// Видимая: рендер берёт узлы, которых в DOM уже нет.
//
// Скрытая и худшая: сразу за рендером идёт подключение STOMP. Он ставит подписки уже ПОСЛЕ
// того, как unmount() разорвал соединение, — то есть заводит живого подписчика, которого
// больше некому снять. Ровно так и получаются две подписки на один адрес и по копии
// каждого сообщения; в роутере против этого уже стоит такой же счётчик поколений.
let mountGeneration = 0;

// key — MinIO objectKey (напр. userimage/42/uuid.png). Разметка отдаёт заглушку с меткой
// data-image-key, байты подставляет hydrateImages через axios (beads gs2): тег <img> не умеет
// послать Authorization, и такой запрос отбивался 401 ещё на ingress.
function avatarHtml(key) {
    return imageTag(key, 'chat-avatar', 'chat avatar');
}

function appendChatMessage({ user_id: senderId, username, timestamp, text, imageurl, image_url, sticker_key }) {
    const container = document.getElementById('chatMessages');
    const mine = String(senderId) === String(user_id);

    // Ряд = аватарка + пузырь, аватарка СНАРУЖИ пузыря (см. .message-row в style2.css).
    // Аватарку показываем у каждого сообщения, а не только у первого в серии одного
    // автора: серию пришлось бы вычислять по предыдущему узлу ленты, а порядок прихода
    // сообщений по STOMP не совпадает с порядком истории — при догрузке и при отбивках
    // группировка разъезжалась бы, и «лишняя» аватарка стоила бы дешевле пропавшей.
    const row = document.createElement('div');
    row.classList.add('message-row');
    row.classList.add(mine ? 'user' : 'bot');

    const msg = document.createElement('div');
    msg.classList.add('message');
    msg.classList.add(mine ? 'user' : 'bot');

    // Стикер рисуется без пузыря (beads a22): фон, рамку и тень снимает класс .sticker,
    // а сама картинка едет тем же путём, что аватарки, — плейсхолдер + hydrateImages,
    // потому что <img> не умеет послать Authorization, а /api/images закрыт auth_request.
    // Пустой ключ приезжает из истории (protobuf-дефолт для string), поэтому проверка
    // не на null, а на непустоту.
    const stickerKey = sticker_key || '';
    const isSticker = stickerKey !== '';
    if (isSticker) msg.classList.add('sticker');

    const img = imageurl ?? image_url;

    // Аватарка отправителя — кнопка: клик открывает его мини-профиль. Ник и ключ картинки
    // едут в dataset, а не интерполяцией в разметку: ник приходит с сервера, и
    // пользовательский текст в HTML здесь не попадает вовсе — по той же причине, что
    // расписана ниже для текста сообщения.
    const avatarBtn = document.createElement('button');
    avatarBtn.type = 'button';
    avatarBtn.className = 'message-avatar-btn';
    avatarBtn.setAttribute('aria-haspopup', 'dialog');
    avatarBtn.setAttribute('aria-label', 'Показать профиль отправителя');
    avatarBtn.dataset.userId = senderId ?? '';
    avatarBtn.dataset.username = username ?? '';
    // Имя атрибута НЕ data-image-key: этой меткой image_loader помечает узлы под подстановку
    // байтов, и кнопка попала бы в выборку hydrateImages как ещё одна картинка.
    avatarBtn.dataset.avatarKey = img ?? '';
    avatarBtn.innerHTML = policy.createHTML(imageTag(img, 'message-avatar', 'аватарка отправителя'));

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
            <strong class="message-username"></strong>
            <strong class="message-senderid"></strong>
            <span class="message-time">${formatMessageTimestamp(timestamp)}</span>
        </div>
        ${isSticker
            ? `<div class="sticker-body">${imageTag(stickerKey, 'sticker-image', 'стикер')}</div>`
            : `<div><span class="message-text"></span></div>`}
    `);
    msg.querySelector('.message-username').textContent = username || 'anon';
    msg.querySelector('.message-senderid').textContent = `ID: ${senderId || 'anon'}`;
    if (!isSticker) {
        msg.querySelector('.message-text').textContent = text ?? '';
    }

    row.append(avatarBtn, msg);
    container.appendChild(row);
    hydrateImages(row);
    container.scrollTop = container.scrollHeight;
}

function renderHeader(data) {
    const title = data.title || chat_title;
    // Название и аватарка стоят в двух местах: шапка чата и правая колонка. Оба
    // заполняются одинаково — какая из них видна, решает media-query.
    for (const id of ['chat-title', 'side-title']) {
        const node = document.getElementById(id);
        if (node) node.textContent = title;
    }

    const avatarHtmlString = data.chatImageUrl === 'pending'
        ? `<div class="spinner-avatar"></div>`
        : avatarHtml(data.chatImageUrl);

    for (const id of ['chat-header-avatar', 'side-avatar']) {
        const box = document.getElementById(id);
        if (!box) continue;
        box.innerHTML = policy.createHTML(avatarHtmlString);
        hydrateImages(box);
    }
}

// Состав чата приезжает вместе с остальными данными экрана (/api/chat, поле members) —
// отдельного запроса при открытии модалки нет. Состав меняется только при создании чата,
// так что перечитывать его по клику незачем.
function renderMembers(members) {
    const list = members || [];
    renderAiRecipients(list);
    renderMembersList(list);
}

function renderAiRecipients(members) {
    const select = document.getElementById('aiUserSelect');
    for (const member of members) {
        // Себя в список «кому ответить через AI» не кладём: просить AI ответить за себя
        // бессмысленно.
        if (member.username === user_name) continue;
        const opt = document.createElement('option');
        opt.value = member.username;
        opt.textContent = member.username;
        select.appendChild(opt);
    }
}

function memberItem(member) {
    const item = document.createElement('li');
    item.className = 'member-row';
    item.innerHTML = policy.createHTML(`
        ${imageTag(member.imageUrl, 'member-avatar', 'аватарка участника')}
        <div class="member-info">
            <span class="member-name"></span>
            <span class="member-id"></span>
        </div>
    `);
    item.querySelector('.member-name').textContent = member.username;
    item.querySelector('.member-id').textContent = `ID: ${member.userId}`;
    // Себя помечаем — в чате на несколько человек с похожими никами иначе непонятно,
    // где ты. textContent, а не разметкой: ник приходит с сервера.
    if (String(member.userId) === String(user_id)) {
        const badge = document.createElement('span');
        badge.className = 'member-badge';
        badge.textContent = 'вы';
        // Ставим МЕЖДУ ником и id, а не в конец: у .member-id стоит flex-basis:100%,
        // он занимает строку целиком, и добавленная после него метка уезжала бы на
        // третью строку вместо того, чтобы стоять рядом с ником.
        item.querySelector('.member-id').before(badge);
    }
    return item;
}

// Состав рисуется в ДВА контейнера: постоянную правую колонку и модалку. Какой из
// них видно, решает media-query, а не JS, — иначе смена ширины окна требовала бы
// перерисовки, а список пришлось бы держать в состоянии модуля.
function renderMembersList(members) {
    const count = members.length;
    const containers = [
        document.getElementById('members-list'),
        document.getElementById('members-panel-list'),
    ];

    for (const title of [document.getElementById('members-title'),
                         document.getElementById('members-panel-title')]) {
        if (title) title.textContent = `Участники (${count})`;
    }

    for (const list of containers) {
        if (!list) continue;
        list.replaceChildren();
        for (const member of members) {
            list.appendChild(memberItem(member));
        }
        hydrateImages(list);
    }
}

// --- модалка состава ---

function openMembers() {
    const overlay = document.getElementById('members-overlay');
    if (!overlay) return;
    overlay.hidden = false;
    // Фокус уезжает на «закрыть»: иначе он остался бы на шапке под затемнением, и Tab
    // ходил бы по элементам чата, которых уже не видно.
    document.getElementById('members-close')?.focus();
}

function closeMembers() {
    const overlay = document.getElementById('members-overlay');
    if (!overlay || overlay.hidden) return;
    overlay.hidden = true;
    // Возвращаем фокус туда, откуда модалку открыли.
    document.getElementById('chat-info-btn')?.focus();
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

// --- стикеры (beads a22) ---

// Стикер — отдельное сообщение: текст в поле ввода не трогается вовсе. Сервер сам
// проверит, что ключ принадлежит отправителю (ChatBoxStompController), и отобьётся на
// /private/{user_id}, если нет, — отдельной обработки ошибки здесь не нужно, её делает
// общий handleChatError.
function sendSticker(objectKey) {
    // Молча не теряем: картинка уже в MinIO, и пользователь вправе знать, что в чат она
    // не ушла (тот же принцип, что у отбивок на SEND, beads isf).
    if (!stompClient) {
        showToast('Нет связи с чатом — стикер не отправлен', 'error');
        return;
    }
    stompClient.send(`/app/chat/send/${chat_id}`, {}, JSON.stringify({
        text: '',
        sticker_key: objectKey,
        imageurl: user_image,
    }));
    closeStickerPanel();
}

function toggleStickerPanel() {
    const panel = document.getElementById('stickerPanel');
    if (!panel) return;
    if (panel.hidden) {
        panel.hidden = false;
        document.getElementById('stickerBtn')?.setAttribute('aria-expanded', 'true');
    } else {
        closeStickerPanel();
    }
}

function closeStickerPanel() {
    const panel = document.getElementById('stickerPanel');
    if (!panel || panel.hidden) return;
    panel.hidden = true;
    document.getElementById('stickerBtn')?.setAttribute('aria-expanded', 'false');
}

function switchStickerTab(name) {
    for (const tab of document.querySelectorAll('.sticker-tab')) {
        const active = tab.dataset.tab === name;
        tab.classList.toggle('is-active', active);
        tab.setAttribute('aria-selected', String(active));
    }
    for (const pane of document.querySelectorAll('.sticker-pane')) {
        pane.hidden = pane.dataset.pane !== name;
    }
    // Набор перечитывается на каждое открытие вкладки, а не кешируется: он меняется от
    // собственных отправок (порядок — по последнему использованию), причём в том числе из
    // другой вкладки браузера. Ответ — до 60 строк, это дешевле рассинхрона.
    if (name === 'mine') loadMyStickers();
}

function loadMyStickers() {
    const grid = document.getElementById('stickerGrid');
    if (!grid) return;
    grid.replaceChildren();
    api.get('/api/stickers')
        .then(response => {
            const keys = response.data || [];
            if (keys.length === 0) {
                const empty = document.createElement('p');
                empty.className = 'sticker-hint';
                empty.textContent = 'Пока пусто. Отправьте картинку — она попадёт сюда.';
                grid.appendChild(empty);
                return;
            }
            for (const key of keys) {
                const cell = document.createElement('button');
                cell.type = 'button';
                cell.className = 'sticker-cell';
                cell.setAttribute('aria-label', 'Отправить стикер');
                // Ключ кладём в data-атрибут, а не в разметку обработчика: он приезжает с
                // сервера, и интерполировать его в HTML незачем.
                cell.dataset.stickerKey = key;
                cell.innerHTML = policy.createHTML(imageTag(key, 'sticker-thumb', 'стикер'));
                grid.appendChild(cell);
            }
            hydrateImages(grid);
        })
        .catch(err => {
            console.error('[chat] не удалось загрузить набор стикеров', err);
            showToast('Не удалось загрузить стикеры', 'error');
        });
}

// Загрузка и отправка одним движением: вкладка «Загрузить» — это и есть «отправить
// картинку». Ответ синхронный и несёт ключ (POST /reactive/api/sticker), поэтому ждать
// события, как это делает аватарка, нечего.
async function uploadAndSendSticker(file) {
    const formData = new FormData();
    formData.append('file', file);
    try {
        const response = await api.post('/reactive/api/sticker', formData);
        const objectKey = response.data?.objectKey;
        if (!objectKey) {
            showToast('Не удалось загрузить картинку', 'error');
            return;
        }
        sendSticker(objectKey);
    } catch (err) {
        console.error('[chat] загрузка стикера не удалась', err);
        showToast(err?.response?.status === 400
            ? 'Такой формат не поддерживается'
            : 'Не удалось загрузить картинку', 'error');
    }
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
    // STOMP-событие картинки несёт objectKey (см. контракт Images-топика).
    if (!message.objectKey || message.objectKey === 'pending') return;
    // Аватарка чата стоит в двух местах — шапка и правая колонка; обновлять надо обе,
    // иначе после загрузки картинки одна из них осталась бы с буквенной заглушкой.
    for (const id of ['chat-header-avatar', 'side-avatar']) {
        const target = document.getElementById(id);
        if (!target) continue;
        target.innerHTML = policy.createHTML(
            avatarHtml(message.objectKey));
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
        getClient: () => stompClient,
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
        getClient: () => stompClient,
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

    // Одно соединение на все три канала и на адрес отбивок (было три отдельных сокета).
    //
    // Спецификация WebSocket запрещает браузеру держать больше одного соединения в состоянии
    // CONNECTING к одному host:port, поэтому хендшейки выстраиваются в очередь и холодный
    // старт стоит N × время_одного_хендшейка. Замер на стенде: один сокет — 2.0 с, четыре
    // параллельно — 1.5 / 3.1 / 4.7 / 6.1 с. Три эндпоинта здесь ничем не отличались друг от
    // друга: StompConfig регистрирует все шесть адресов одним циклом с одинаковой
    // конфигурацией, брокер один, а доступ разграничивает StompAuthChannelInterceptor по
    // destination подписки, а не по эндпоинту.
    //
    // Поканальные бюджеты повторов в channelState при этом остаются осмысленными: сервер
    // роняет SUBSCRIBE независимо для каждого адреса (PER_CHAT_PREFIXES), и это не зависит от
    // того, по скольким транспортам разложены подписки.
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
        subscribeChannel('typing');
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
    const myGeneration = ++mountGeneration;
    chat_id = params.id;
    chat_title = params.title || '';

    user_id = null;
    user_name = null;
    user_image = null;
    typingUsers = [];
    stompClient = null;
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

    // Состав чата: открыть по шапке, закрыть крестиком, кликом по затемнению или Escape.
    document.getElementById('chat-info-btn').addEventListener('click', openMembers, { signal: ac.signal });
    document.getElementById('members-close').addEventListener('click', closeMembers, { signal: ac.signal });
    document.getElementById('members-overlay').addEventListener('click', (e) => {
        // Только по самому затемнению: клик внутри карточки всплывает сюда же, и без
        // проверки модалка закрывалась бы от любого тычка по списку.
        if (e.target.id === 'members-overlay') closeMembers();
    }, { signal: ac.signal });
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape') closeMembers();
    }, { signal: ac.signal });

    // Стикеры (beads a22). Все слушатели вешаются с ac.signal, как и остальные на экране,
    // — снимать их вручную в unmount() не нужно.
    document.getElementById('stickerBtn').addEventListener('click', toggleStickerPanel, { signal: ac.signal });
    document.getElementById('stickerClose').addEventListener('click', closeStickerPanel, { signal: ac.signal });
    for (const tab of document.querySelectorAll('.sticker-tab')) {
        tab.addEventListener('click', () => switchStickerTab(tab.dataset.tab), { signal: ac.signal });
    }
    document.getElementById('stickerPick').addEventListener('click', () => {
        document.getElementById('stickerFile').click();
    }, { signal: ac.signal });
    document.getElementById('stickerFile').addEventListener('change', (e) => {
        const file = e.target.files && e.target.files[0];
        // value чистится сразу: без этого выбор ТОГО ЖЕ файла второй раз не поднимает
        // событие change, и повторная отправка той же картинки молча не работала бы.
        e.target.value = '';
        if (file) uploadAndSendSticker(file);
    }, { signal: ac.signal });
    // Клик по миниатюре — делегированием на сетку: ячейки создаются асинхронно, после
    // ответа /api/stickers, и вешать слушатель на каждую значило бы плодить их на каждое
    // открытие вкладки.
    document.getElementById('stickerGrid').addEventListener('click', (e) => {
        const cell = e.target.closest('.sticker-cell');
        if (cell) sendSticker(cell.dataset.stickerKey);
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
        // Токен вперёд, запросы данных параллельно — обоснование см. в chatlist.view.js:
        // без токена первый запрос гарантированно ловит 401 (лишний круг), а параллелить их
        // без токена нельзя вовсе — два одновременных 401 запускают гонку обновления,
        // которую сервер трактует как повторное использование refresh-токена.
        //
        // user_id проставляется ДО отрисовки сообщений: appendChatMessage сравнивает с ним
        // отправителя, чтобы выбрать класс пузыря (свой/чужой). При параллельных запросах
        // порядок гарантирован тем, что Promise.all отдаёт оба результата разом, а рисуем мы
        // уже после.
        // Отказ обмена — отдельная ветка: раньше на /welcome уводил интерцептор axios после
        // 401 на /api/me, теперь токен берётся раньше и интерцептор в этом не участвует.
        // Без явного увода аноним по прямой ссылке на чат получил бы тост «Не удалось
        // открыть чат» и переход на список чатов, где его ждало бы то же самое.
        let token;
        try {
            token = await ensureAccessToken();
        } catch (e) {
            console.error("chat bootstrap failed: нет живой сессии", e);
            await navigate('/welcome');
            return;
        }

        // Экран уже не наш — ни рисовать, ни подписываться нельзя.
        if (myGeneration !== mountGeneration) return;

        const [me, data] = await Promise.all([
            api.get('/api/me').then(r => r.data),
            api.get('/api/chat', { params: { id: chat_id, title: chat_title } }).then(r => r.data),
        ]);
        if (myGeneration !== mountGeneration) return;

        user_id = String(me.userId);
        user_name = me.username;
        user_image = me.imageUrl;

        renderHeader(data);
        renderMembers(data.members);
        for (const msg of data.messages || []) {
            appendChatMessage(msg);
        }

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
    // Бутстрап, который всё ещё висит на await, обязан остановиться: счётчик двигаем
    // первым делом, до того как разорвём соединение и снимем таймеры.
    mountGeneration++;
    // blob-URL живут до отзыва (beads gs2) — без этого вкладка копила бы их при каждом
    // переходе между чатами.
    releaseImages();
    if (stomp) stomp.disconnectAll();
    clearTimeout(typingTimeout);
    // Таймеры повтора подписки (beads 8wh → обобщено на все три канала в 8r7) гасим по
    // тому же образцу, что и typingTimeout: клиент stompClient не
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
