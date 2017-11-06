var subtitles =
  [ 'Do you want to cancel? – Yes / Cancel', 
    'Select purchase to purchase for $0.00 – PURCHASE / CANCEL', 
    'This isn not text, this is a link. No not press.',
    'Do not refresh this page, do not click Back',
    'Error occured — OK',
    'Password must be 8 characters including 1 uppercase letter and 1 number',
    'Are you sure you want to exist? — YES / NO',
    'Open in app',
    'Warning: No pixels were selected',
    'You need to be logged in to log out. Please log in to log out.',
    'Cancel changes? – Cancel / Change', 
    'Cancel subscription? – Cancel / Yes',
    'Please, try again later',
    'You need to login to unsubscribe from spam',
    'Update Java Runtime?',
    'Your PC will automatically restart in one minute',
    'grumpy.website wants to: Show notifications',
    'Your browser is too old',
    'How likely are you to recommend grumpy.website to your friends?',
    'System has recovered from a serious error',
    'Are you in Boston? — Yes / Select another',
    'Hi Carol, thanks for signing up! My name is Kevin...' ],
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
