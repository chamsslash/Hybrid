import api from  './axios.js';
import {getFingerprintData} from "/meta_catcher.js";
import {setAccessToken} from "/inmemory.js";


(async () => {
    try {
        console.log("[callback.js] Запуск IIFE");

        // 2. Парсим параметры из URL
        const params = new URLSearchParams(window.location.search);
        const code = params.get('code');
        const state = params.get('state');
        console.log("[callback.js] Параметры из URL:", { code, state });

        // 3. Получаем fingerprint ID
        const meta = await getFingerprintData();
        if (!code || !state) {
            console.warn("[callback.js] Отсутствует code или state. Перенаправление на /welcome");
            console.log(code)
            console.log(state)
            window.location.href = '/welcome';
            return;
        }
        // const {api} =await import("./axios.js")
        // 5. Формируем тело запроса
        const formData = new URLSearchParams();
        formData.append('code', code);
        formData.append('state', state);
        formData.append('FpComponents',JSON.stringify(meta.components))
        console.log("[callback.js] formData сформирован:", formData.toString());

        // 6. Отправляем запрос на сервер
        console.log("[callback.js] Отправляем POST /verifylogin");
        const response = await api.post(
            '/verifylogin', // 1. URL
            formData,       // 2. Тело запроса (data)
            {               // 3. ✨ ПРАВИЛЬНЫЙ ОБЪЕКТ КОНФИГУРАЦИИ ✨
                headers: {  // У него должно быть свойство "headers"
                    'Content-Type': 'application/x-www-form-urlencoded',
                    'X-CSRF-TOKEN': document.cookie.split('; ').find(row => row.startsWith("XSRF-TOKEN"+ '='))?.split('=')[1],
                    'X-Fingerprint': meta.fingerprint,
                    "X-SecureUUID": meta.secureUUID,
                    "X-Client-Meta": JSON.stringify(meta.clientMeta)
                }, withCredentials: true
            }
        );

        console.log("[callback.js] Ответ от сервера получен. Статус:", response.status, response.statusText);
        // 7. Проверяем редирект
        try {
            const responseBody = response.data;
            if (responseBody?.accessToken) {
                setAccessToken(responseBody.accessToken);
            }
            const redirectUri = responseBody.redirectUri;
            if (  redirectUri) {

                console.log("[callback.js] Выполняем редирект на:", redirectUri);
                window.location.href = redirectUri;}
        } catch (e) {
            console.error("[callback.js] Ошибка:",e);
            const errorBody = await response.text();
            console.error("Статус:", response.status);
            console.error("Ответ:", errorBody);
            // window.location.href = "/welcome?error="+response.status+":"+errorBody;
        }

    } catch (error) {
        console.error("[callback.js] Критическая ошибка:", error);
        // window.location.href = '/welcome?error="CriticalError: '+error;
    }
})();
