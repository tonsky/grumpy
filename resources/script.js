window.onload = function() {
  reloadSubtitle();
  document.getElementById('site_subtitle').onclick = reloadSubtitle;
}

function reloadSubtitle() {
  var subtitles = [
  'Вы уверены, что хотите отменить? – Да / Нет / Отмена', 
  'Select purchase to purchase for $0.00 – PURCHASE / CANCEL', 
  'Это не текст, это ссылка. Не нажимайте на ссылку.',
  'Не обновляйте эту страницу! Не нажимайте НАЗАД',
  'Произошла ошибка OK',
  'Пароль должен содержать заглавную букву и специальный символ',
  'Are you sure you want to exist? — YES / NO',
  'Открыть в приложении',
  'Warning: No pixel were selected'
  ];
  var subtitle = subtitles[Math.floor(Math.random() * subtitles.length)];
  var div = document.getElementById('site_subtitle');
  div.innerHTML = subtitle;
}