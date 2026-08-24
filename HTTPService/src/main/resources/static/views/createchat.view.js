import { navigate } from "/router.js";
import api from "/axios.js";
import { ensureAccessToken } from "/auth.js";

// Вью создания чата (перенесена из static/chatcreate.js под контракт mount/unmount SPA-роутера).
// Разметка формы — из templates/chatcreatepage.html.

const CREATECHAT_HTML = `
    <div class="post-feed">
        <button type="button" id="back-btn" class="back-button" aria-label="Назад к списку чатов">←</button>

        <h2 style="text-align: center;">Отправка ChatData</h2>

        <form id="chatForm" class="add-comment" enctype="multipart/form-data">

            <!-- name НЕ "title": DOMPurify (см. policy.createHTML/trusted_policy.js) по
                 умолчанию защищается от DOM clobbering и молча вырезает name="title" —
                 совпадение с document.title. Реальный ключ "title", который ждёт сервер
                 (WEBFLUX_Service.handleCreateChat), подставляется вручную при сабмите. -->
            <input type="text" name="chatTitle" placeholder="Название чата" required>

            <!-- Поле для выбора файла -->
            <label for="imageUpload">Загрузить изображение:</label>
            <input type="file" name="file" id="imageUpload" accept="image/*" />

            <div id="userFields">
                <!-- Сюда будут добавляться блоки пользователей -->
            </div>

            <button type="button" id="addUserBtn">+ Добавить пользователя</button>
            <br><br>
            <button type="submit">Отправить</button>
        </form>

        <p id="result" style="margin-top: 10px; font-weight: bold;"></p>
    </div>
`;

let ac = null;
let userCount = 0;

function addUserField() {
    const container = document.getElementById("userFields");

    const wrapper = document.createElement("div");
    wrapper.classList.add("comment");

    wrapper.innerHTML = policy.createHTML(`
            <label>
                User ${userCount + 1} Name:
                <input type="text" name="userlistname" placeholder="User Name" required />
            </label>
        `);

    container.appendChild(wrapper);
    userCount++;
}

export async function mount(params) {
    ac = new AbortController();
    userCount = 0;

    // Шелл /reactive/createchat публичен на ingress (beads 52u) — иначе обычная
    // навигация (F5, закладка, прямая ссылка) не несёт Authorization и nginx
    // отдаёт голую страницу 401 вместо приложения. Доступ проверяем на клиенте:
    // без живой refresh-сессии уводим на /welcome, чтобы аноним не видел форму.
    // Сама мутация защищена auth_request на /reactive/api/createchat.
    // Уводим клиентским переходом: /welcome — такой же роут этого же shell'а,
    // и терять тут нечего (ensureAccessToken упал — значит токена в памяти и не было),
    // а window.location.href стоил бы лишней полной перезагрузки app-shell.
    try {
        await ensureAccessToken();
    } catch (e) {
        console.error("createchat bootstrap failed:", e);
        await navigate("/welcome");
        return;
    }

    const app = document.getElementById("app");
    app.innerHTML = policy.createHTML(CREATECHAT_HTML);

    // Возврат явным маршрутом, а не history.back(): на форму создания чата заходят и по
    // прямой ссылке, и тогда в истории возвращаться некуда. Целевой экран у неё всегда
    // один — список чатов, откуда её и открывают.
    document.getElementById("back-btn").addEventListener("click", () => {
        navigate("/reactive/chatlist");
    }, { signal: ac.signal });

    document.getElementById("addUserBtn").addEventListener("click", addUserField, { signal: ac.signal });
    addUserField();

    document.getElementById("chatForm").addEventListener("submit", async function (e) {
        e.preventDefault();
        const formData = new FormData(e.target);
        formData.set('title', formData.get('chatTitle'));
        formData.delete('chatTitle');

        try {
            // api-интерцептор приложит Authorization и сделает refresh+retry на 401
            await api.post("/reactive/api/createchat", formData);
            navigate("/reactive/chatlist");
        } catch (error) {
            console.error('Ошибка отправки:', error);
            const resultElement = document.getElementById("result");
            resultElement.textContent = error?.response?.data || "Ошибка создания чата";
            resultElement.style.color = "red";
        }
    }, { signal: ac.signal });
}

export function unmount() {
    if (ac) ac.abort();
    ac = null;
}
