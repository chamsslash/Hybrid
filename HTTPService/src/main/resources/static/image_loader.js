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
const pinned = new Set();     // ключи, которые releaseImages() не трогает

function isUsableKey(key) {
    // "pending" — маркер «картинка ещё грузится в MinIO», который кладёт бэкенд.
    return Boolean(key) && key !== "pending";
}

// Подложки буквенных аватарок. Выбор детерминированный по seed (id чата или
// пользователя), а не случайный и не по порядку в списке: один и тот же объект
// обязан быть одного цвета в списке, в чате и в рельсе, иначе цвет читается как
// значащий признак, которым он не является.
const LETTER_GRADIENTS = [
    "linear-gradient(135deg,#7b61ff,#b14bf4)",
    "linear-gradient(135deg,#ff7b54,#fd5a48)",
    "linear-gradient(135deg,#2bb8a3,#35d07f)",
    "linear-gradient(135deg,#4a7bff,#6bb8ff)",
    "linear-gradient(135deg,#e0518f,#ff6b9d)",
    "linear-gradient(135deg,#f2a341,#f4c45e)",
];

function gradientFor(seed) {
    const s = String(seed ?? "");
    let h = 0;
    for (let i = 0; i < s.length; i++) {
        h = (h * 31 + s.charCodeAt(i)) >>> 0;
    }
    return LETTER_GRADIENTS[h % LETTER_GRADIENTS.length];
}

function firstLetter(text) {
    const t = String(text ?? "").trim();
    return t ? t[0].toUpperCase() : "?";
}

/**
 * Разметка картинки. Сразу отдаёт заглушку и помечает узел ключом: реальные байты
 * подставит hydrateImages после вставки узла в DOM. Так разметка остаётся синхронной
 * (её собирают через policy.createHTML в шаблонных строках), а сеть — асинхронной.
 * data-* атрибуты DOMPurify пропускает, поэтому ключ переживает санитайзер.
 *
 * Когда пригодного ключа нет, отдаётся не заглушка-картинка, а буква на цветной
 * подложке: пустой кружок ничего не сообщал, а одинаковая для всех заглушка
 * делала список чатов неразличимым.
 *
 * @param seed  чем солить выбор цвета (id чата/пользователя)
 * @param label откуда брать букву (название чата, ник)
 */
export function imageTag(key, className = "chat-avatar", alt = "avatar", seed = null, label = "") {
    if (isUsableKey(key)) {
        return `<img src="${IMAGE_PLACEHOLDER}" data-image-key="${key}" alt="${alt}" class="${className}">`;
    }
    const seedValue = seed ?? label ?? alt;
    return `<span class="letter-avatar ${className}" role="img" aria-label="${alt}"`
        + ` style="background:${gradientFor(seedValue)}">${firstLetter(label)}</span>`;
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
