import api from  './axios.js';
import {getFingerprintData} from "/meta_catcher.js";
import { setAccessToken } from "/inmemory.js";
document.addEventListener('DOMContentLoaded', async () => {

    // --- ОБЪЯВЛЯЕМ ВСЕ ПЕРЕМЕННЫЕ ---
    const googleLoginBtn = document.getElementById('google-login-btn');
    const registerLink = document.getElementById('register-link');
    const regularLoginForm = document.getElementById('regular-login-form');
    const responseDiv = document.getElementById('response-div');
    let hideTimeout; // Для таймера скрытия ошибок
    const meta = await getFingerprintData();
    if(registerLink){
        registerLink.addEventListener('click',async (event)=>{
            event.preventDefault();
            googleLoginBtn.disabled = true;
            googleLoginBtn.textContent = 'Подготовка...';
            if (responseDiv) responseDiv.style.opacity = '0';
            window.location.href="/registerpage"
        })
    }
    if (googleLoginBtn) {
        googleLoginBtn.addEventListener('click', async (event) => {
            event.preventDefault();
            googleLoginBtn.disabled = true;
            googleLoginBtn.textContent = 'Подготовка...';
            if (responseDiv) responseDiv.style.opacity = '0';
            // const {api} =await import("./axios.js")


            const response = await api.post('/startauth',
                new URLSearchParams({FpComponents: JSON.stringify(meta.components)}),
                {
                    headers: {
                        'X-Fingerprint': meta.fingerprint,
                        "X-SecureUUID": meta.secureUUID,
                        'X-CSRF-TOKEN': document.cookie.split('; ').find(row => row.startsWith("XSRF-TOKEN"+ '='))?.split('=')[1],
                        "X-Client-Meta": JSON.stringify(meta.clientMeta)
                    }
                }
            );

            const data = response.data;

            if (data.redirectUrl) {
                window.location.href = data.redirectUrl;
            } else {
                throw new Error("Сервер не вернул URL для редиректа.");
            }

        });
    }

    // --- ЛОГИКА ДЛЯ ОБЫЧНОЙ ФОРМЫ ---
    if (regularLoginForm) {
        regularLoginForm.addEventListener('submit', async (event) => {
            event.preventDefault();
            try {

                // ИСПРАВЛЕН ПОРЯДОК ОПЕРАЦИЙ
                const params = new URLSearchParams(new FormData(regularLoginForm));
                params.set("FpComponents",JSON.stringify(meta.components))
                const csrfTokenCookie = document.cookie.split('; ').find(row => row.startsWith('XSRF-TOKEN='));
                const csrfToken = csrfTokenCookie ? csrfTokenCookie.split('=')[1] : null;


                console.log(csrfToken)
                const response = await api.post('/reactive/login',
                    params,
                    {
                        headers: {
                            'X-Fingerprint': meta.fingerprint,
                            "X-SecureUUID": meta.secureUUID,
                            "X-Client-Meta": JSON.stringify(meta.clientMeta),
                            'X-CSRF-TOKEN': csrfToken,


                        }
                    }
                );
                if (response.data.redirectUri) {
                    if (response.data.accessToken) {
                        setAccessToken(response.data.accessToken);
                    }
                    window.location.href =  response.data.redirectUri
                } else {
                    const errorText = await response.text();
                    throw new Error(errorText || "Неверные учетные данные.");
                }
            } catch (error) {
                handleError(error.message);
            }
        });
    }

    // --- ФУНКЦИЯ ДЛЯ ОТОБРАЖЕНИЯ ОШИБОК ---
    function handleError(message) {
        if (responseDiv) {
            responseDiv.textContent = message;
            responseDiv.style.opacity = '1';
            if (hideTimeout) clearTimeout(hideTimeout);
            hideTimeout = setTimeout(() => {
                responseDiv.style.opacity = '0';
            }, 5000);
        }
    }
});
