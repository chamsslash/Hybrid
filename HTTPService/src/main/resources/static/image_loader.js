import api from "/axios.js";

// Загрузка картинок из MinIO в обход тега <img> (beads gs2).
//
// Почему не просто src="/api/images/{key}": тег <img> формирует запрос сам, мимо JS и мимо
// axios-интерцептора (axios.js), поэтому заголовок Authorization в нём не появится никогда.
// Путь /api сидит за auth_request на ingress, а JwtCheckController принимает токен только из
// Authorization или из cookie "access", которая в коде лишь удаляется (MVC_Service) и нигде
// не выставляется. В итоге ЛЮБАЯ картинка отбивалась 401 ещё на nginx — проверено живьём:
// fetch без заголовка 401, тот же ключ с Bearer 200 image/png, new Image().src → onerror.
//
// Поэтому байты тянет axios (он приложит Bearer и сделает refresh+retry на 401), а в src
// подставляется blob-URL. Кеш по objectKey обязателен: одна и та же аватарка автора
// встречается в каждом его сообщении, без кеша это был бы запрос на каждое сообщение.
const objectUrls = new Map(); // objectKey -> Promise<string|null>
const pinned = new Set();     // ключи, которые releaseImages() не трогает

function isUsableKey(key) {
    // "pending" — маркер «картинка ещё грузится в MinIO», который кладёт бэкенд.
    return Boolean(key) && key !== "pending";
}

/**
 * Разметка аватарки. Возвращает не <img>, а плейсхолдер-<span>: реальные байты
 * подставит hydrateImages после вставки узла в DOM, заменив span на <img>. Так
 * разметка остаётся синхронной (её собирают через policy.createHTML в шаблонных
 * строках), а сеть — асинхронной. data-* атрибуты DOMPurify пропускает, поэтому
 * метка переживает санитайзер.
 *
 * У плейсхолдера два состояния, и оба — на одном и том же элементе:
 *   avatar-ph-loading — байты в пути, по подложке идёт блик;
 *   avatar-ph-empty   — аватарки нет или она не доехала, нейтральный силуэт.
 *
 * Раньше на месте обоих стояла картинка /images/rofl-cat.jpg. Она не только
 * висела в каждой ячейке, пока шла загрузка (на публичном стенде это около
 * секунды на аватарку), но и ОСТАВАЛАСЬ НАВСЕГДА, если байты не приходили:
 * 403 на чужой объект, 404, обрыв сети — всё это выглядело как «у пользователя
 * такая аватарка». Теперь неудача попадает в то же состояние, что и её
 * отсутствие, и ничем не притворяется.
 */
export function imageTag(key, className = "chat-avatar", alt = "avatar") {
    if (!isUsableKey(key)) {
        return `<span class="avatar-ph avatar-ph-empty ${className}" role="img" aria-label="${alt}"></span>`;
    }
    return `<span class="avatar-ph avatar-ph-loading ${className}" role="img" aria-label="${alt}"`
        + ` data-image-key="${key}" data-image-class="${className}" data-image-alt="${alt}"></span>`;
}

function objectUrlFor(key) {
    if (!objectUrls.has(key)) {
        objectUrls.set(key, api.get(`/api/images/${key}`, { responseType: "blob" })
            .then((response) => URL.createObjectURL(response.data))
            // 403 на чужой объект — штатный ответ после beads e1o, вью на нём ломаться не
            // должна: остаётся заглушка. Сюда же попадают 404 и сетевые сбои.
            .catch(() => null));
    }
    return objectUrls.get(key);
}

/** Подставляет байты во все помеченные узлы поддерева. Вызывать после вставки в DOM. */
export function hydrateImages(root) {
    const scope = root || document;
    scope.querySelectorAll("[data-image-key]").forEach((node) => {
        const key = node.getAttribute("data-image-key");
        const className = node.getAttribute("data-image-class") || "chat-avatar";
        const alt = node.getAttribute("data-image-alt") || "";
        // Снимаем метку сразу: повторный hydrateImages по тому же поддереву не должен
        // ставить второй обработчик на тот же узел.
        node.removeAttribute("data-image-key");
        objectUrlFor(key).then((url) => {
            // Узел мог уехать из DOM, пока шёл запрос (перерисовка списка, смена вью).
            if (!node.isConnected) {
                return;
            }
            if (!url) {
                // Байты не пришли. Показываем ровно то же, что при отсутствующей
                // аватарке: пульсация навсегда означала бы «вот-вот загрузится».
                node.classList.replace("avatar-ph-loading", "avatar-ph-empty");
                return;
            }
            const img = document.createElement("img");
            img.className = className;
            img.alt = alt;
            img.src = url;
            node.replaceWith(img);
        });
    });
}

/**
 * Освобождает blob-URL. Без этого каждая перезагрузка вью оставляла бы объекты в памяти
 * вкладки до её закрытия — браузер держит blob живым, пока URL не отозван.
 * Вызывается из unmount вью.
 */
export function releaseImages() {
    for (const [key, pending] of objectUrls.entries()) {
        // Закреплённые картинки живут дольше вью. Аватарка в навигационном рельсе
        // рисуется один раз на вкладку и не перерисовывается при переходах, а
        // releaseImages() зовут из unmount ЧЕТЫРЕ вью из четырёх — без этой
        // проверки первый же переход отзывал бы её blob-URL, и в рельсе
        // оставалась битая картинка до перезагрузки страницы.
        if (pinned.has(key)) continue;
        pending.then((url) => {
            if (url) {
                URL.revokeObjectURL(url);
            }
        });
        objectUrls.delete(key);
    }
}

/** Пометить ключ как переживающий releaseImages(). */
export function pinImage(key) {
    if (isUsableKey(key)) {
        pinned.add(key);
    }
}
