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
export const IMAGE_PLACEHOLDER = "/images/rofl-cat.jpg";

const objectUrls = new Map(); // objectKey -> Promise<string|null>

function isUsableKey(key) {
    // "pending" — маркер «картинка ещё грузится в MinIO», который кладёт бэкенд.
    return Boolean(key) && key !== "pending";
}

/**
 * Разметка картинки. Сразу отдаёт заглушку и помечает узел ключом: реальные байты
 * подставит hydrateImages после вставки узла в DOM. Так разметка остаётся синхронной
 * (её собирают через policy.createHTML в шаблонных строках), а сеть — асинхронной.
 * data-* атрибуты DOMPurify пропускает, поэтому ключ переживает санитайзер.
 */
export function imageTag(key, className = "chat-avatar", alt = "avatar") {
    return isUsableKey(key)
        ? `<img src="${IMAGE_PLACEHOLDER}" data-image-key="${key}" alt="${alt}" class="${className}">`
        : `<img src="${IMAGE_PLACEHOLDER}" alt="${alt}" class="${className}">`;
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
    scope.querySelectorAll("img[data-image-key]").forEach((node) => {
        const key = node.getAttribute("data-image-key");
        // Снимаем метку сразу: повторный hydrateImages по тому же поддереву не должен
        // ставить второй обработчик на тот же узел.
        node.removeAttribute("data-image-key");
        objectUrlFor(key).then((url) => {
            // Узел мог уехать из DOM, пока шёл запрос (перерисовка списка, смена вью).
            if (url && node.isConnected) {
                node.src = url;
            }
        });
    });
}

/**
 * Освобождает blob-URL. Без этого каждая перезагрузка вью оставляла бы объекты в памяти
 * вкладки до её закрытия — браузер держит blob живым, пока URL не отозван.
 * Вызывается из unmount вью.
 */
export function releaseImages() {
    for (const pending of objectUrls.values()) {
        pending.then((url) => {
            if (url) {
                URL.revokeObjectURL(url);
            }
        });
    }
    objectUrls.clear();
}
