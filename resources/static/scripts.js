var subtitles =
  [ 'Отменить изменения? – Отменить / Отмена', 
    'Select purchase to purchase for $0.00 – PURCHASE / CANCEL', 
    'Это не текст, это ссылка. Не нажимайте на ссылку.',
    'Не обновляйте эту страницу! Не нажимайте НАЗАД',
    'Произошла ошибка — OK',
    'Пароль должен содержать заглавную букву и специальный символ',
    'Are you sure you want to exist? — YES / NO',
    'Открыть в приложении',
    'Warning: No pixels were selected',
    'You need to be logged in to log out. Please log in to log out.',
    'Отменить изменения? – Отмена / Изменить', 
    'Отменить подписку? – Отменить / Да',
    'Please, try again later',
    'You need to login to unsubscribe from spam',
    'Update Java Runtime?',
    'Компьютер будет перезагружен через 15 минут',
    'grumpy.website запрашивает разрешение на: Показывать оповещения',
    'Ваш браузер устарел' ],
  subtitle_el = document.querySelector('.subtitle > span');


function reload_subtitle() {
  do {
    var subtitle = subtitles[Math.floor(Math.random() * subtitles.length)];
  } while (subtitle === subtitle_el.innerText);
  subtitle_el.innerHTML = subtitle;
}


window.addEventListener("load", function() {
  subtitle_el.onclick = reload_subtitle;
  reload_subtitle();
  if (document.cookie.indexOf("grumpy_user=") >= 0) {
    document.body.classList.remove("anonymous");
  }
});
