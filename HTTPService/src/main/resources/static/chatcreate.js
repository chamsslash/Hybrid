import api from "/axios.js";

let userCount = 0;

function addUserField() {
    const container = document.getElementById("userFields");

    const wrapper = document.createElement("div");
    wrapper.classList.add("comment");

    wrapper.innerHTML = `
            <label>
                User ${userCount + 1} Name:
                <input type="text" name="userlistname" placeholder="User Name" required />
            </label>
        `;

    container.appendChild(wrapper);
    userCount++;
}

document.getElementById("addUserBtn").addEventListener("click", addUserField);
addUserField();

document.getElementById("chatForm").addEventListener("submit", async function (e) {
    e.preventDefault();
    const formData = new FormData(e.target);

    try {
        // api-интерцептор приложит Authorization и сделает refresh+retry на 401
        const response = await api.post("/reactive/createchat", formData);
        if (response.request?.responseURL && response.request.responseURL !== window.location.href) {
            window.location.href = response.request.responseURL;
            return;
        }
        window.location.href = "/reactive/chatlist";
    } catch (error) {
        console.error('Ошибка отправки:', error);
        const resultElement = document.getElementById("result");
        resultElement.textContent = error?.response?.data || "Ошибка создания чата";
        resultElement.style.color = "red";
    }
});
