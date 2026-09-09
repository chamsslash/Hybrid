// Точка входа app-shell. Регистрирует роуты и стартует роутер.
// trusted_policy.js подключён отдельным <script> в app.html (обычный скрипт,
// не ES-модуль): импортировать его здесь нельзя — повторное выполнение
// вызовет trustedTypes.createPolicy('default', ...) второй раз и упадёт.
import { registerRoute, start } from "/router.js";

registerRoute("/welcome",             () => import("/views/welcome.view.js"));
registerRoute("/registerpage",        () => import("/views/register.view.js"));
registerRoute("/authcallback",        () => import("/views/callback.view.js"));
registerRoute("/reactive/chatlist",   () => import("/views/chatlist.view.js"));
registerRoute("/reactive/chat",       () => import("/views/chat.view.js"));
registerRoute("/reactive/createchat", () => import("/views/createchat.view.js"));

start();
