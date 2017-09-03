window.onload = function() {
  reloadSubtitle();
  document.querySelector('.subtitle > span').onclick = reloadSubtitle;
  if (document.cookie.indexOf("grumpy_user=") >= 0) {
    document.body.classList.remove("anonymous");
  }
}

function reloadSubtitle() {
  var subtitles = [
  'Отменить изменения? – Отменить / Отмена', 
  'Select purchase to purchase for $0.00 – PURCHASE / CANCEL', 
  'Это не текст, это ссылка. Не нажимайте на ссылку.',
  'Не обновляйте эту страницу! Не нажимайте НАЗАД',
  'Произошла ошибка — OK',
  'Пароль должен содержать заглавную букву и специальный символ',
  'Are you sure you want to exist? — YES / NO',
  'Открыть в приложении',
  'Warning: No pixels were selected',
  'You need to be logged in to log out. Please log in to log out.'
  ];
  var subtitle = subtitles[Math.floor(Math.random() * subtitles.length)];
  var div = document.querySelector('.subtitle > span');
  div.innerHTML = subtitle;
}