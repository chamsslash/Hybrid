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

// Сразу добавляем одно поле при загрузке
window.onload = addUserField;

document.getElementById("chatForm").addEventListener("submit", function (e) {
    e.preventDefault();
    const form = e.target;
    const formData = new FormData(form); // Формируем FormData, которая включает все данные формы, включая файл

    fetch("/reactive/createchat", {
        method: "POST",
        body: formData
    })
        .then(response => {
            if (response.redirected) {
                window.location.href = response.url;
            } else {
                return response.text(); // Или .json(), если нужно
            }
        })
        .then(data => {
            if (data) {
                const resultElement = document.getElementById("result");
                resultElement.textContent = data;
                resultElement.style.color = "red"; // Можно для ошибок подсвечивать
            }
        })
        .catch(error => {
            console.error('Ошибка отправки:', error);
        });
});