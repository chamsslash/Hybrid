import {getFingerprintData} from "/meta_catcher.js";
import api from "./axios.js";
import { setAccessToken } from "/inmemory.js";

document.addEventListener('DOMContentLoaded', function () {
    const registerForm = document.getElementById('register-form');
    const responseDiv = document.getElementById('register-response');

    // --- ИСПРАВЛЕНИЕ №1: Получаем CSRF-токен из правильного места ---
    const csrfInput = document.querySelector('input[name="_csrf"]');
    if (!csrfInput) {
        console.error("CSRF токен не найден в форме!");
        return; // Прерываем выполнение, если токена нет
    }
    const csrfToken = csrfInput.value;
    // Имя заголовка для CSRF обычно стандартное, но его тоже можно вынести в input
    const csrfHeader = "X-CSRF-TOKEN";

    if (!registerForm) {
        console.error("Форма регистрации не найдена!");
        return;
    }

    registerForm.addEventListener('submit', async function (e) {
        // --- ИСПРАВЛЕНИЕ №2: Правильный порядок действий ---

        // ШАГ 1: Всегда вызывается первым!
        e.preventDefault();

        let meta; // Объявляем переменную meta заранее
        try {
            // ШАГ 2: Сначала получаем фингерпринт
            meta = await getFingerprintData();
        } catch (error) {
            console.error("Ошибка при получении фингерпринта:", error);
            responseDiv.textContent = "Не удалось получить отпечаток браузера. Пожалуйста, попробуйте снова.";
            responseDiv.style.opacity = '1';
            return; // Прерываем выполнение
        }

        // ШАГ 3: Теперь, когда meta существует, собираем данные формы
        const formData = new FormData(registerForm);
        formData.append("FpComponents", JSON.stringify(meta.components));

        // ШАГ 5: Отправляем запрос
        try {
            const response = await api.post('/reactive/register',
                formData,
                {
                    headers: {
                        'X-Fingerprint': meta.fingerprint,
                        "X-SecureUUID": meta.secureUUID,
                        "X-Client-Meta": JSON.stringify(meta.clientMeta),
                        'X-CSRF-TOKEN': document.cookie.split('; ').find(row => row.startsWith("XSRF-TOKEN"+ '='))?.split('=')[1],                    }
                }
            );

            // ШАГ 6: Обрабатываем ответ
            if (response.data.redirectUri) {
                if (response.data.accessToken) {
                    setAccessToken(response.data.accessToken);
                }
                window.location.href = response.data.redirectUri;
                return;
            }
            // ... другая логика обработки ответа

        } catch (error) {
            console.error("Ошибка при отправке формы:", error);
            responseDiv.textContent = "Ошибка запроса: " + (error.response?.data || error.message);
            responseDiv.style.opacity = "1";
        }
    });
});
