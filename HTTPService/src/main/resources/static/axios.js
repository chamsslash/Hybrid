// src/api.js


// 1. Создаем экземпляр Axios с базовыми настройками
const api = axios.create({
     baseURL: 'http://localhost:2009'
})

export default api;
