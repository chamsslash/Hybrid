// src/api.js


// 1. Создаем экземпляр Axios с базовыми настройками
const api = axios.create({
     // Используем тот же origin, что и страница (ингресс/прокси сами раскидают по сервисам)
     baseURL: ''
})

export default api;
