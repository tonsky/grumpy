var subtitles =
  [ 'Do you want to cancel? – YES / CANCEL', 
    'Select purchase to purchase for $0.00 – PURCHASE / CANCEL', 
    'This isn not text, this is a link. No not press.',
    'Do not refresh this page, do not click Back',
    'Error occured — OK',
    'Password must be 8 characters including 1 uppercase letter and 1 number',
    'Are you sure you want to exist? — YES / NO',
    'Open in app',
    'Warning: No pixels were selected',
    'You need to be logged in to log out. Please log in to log out.',
    // 'Cancel changes? – CANCEL / CHANGE', 
    'Cancel subscription? – YES / CANCEL',
    'Please, try again later',
    'You need to login to unsubscribe',
    'Update Java Runtime?',
    'Your PC will automatically restart in one minute',
    'grumpy.website wants to: Show notifications',
    'You are using an outdated browser',
    'How likely are you to recommend grumpy.website to your friends?',
    'The system has recovered from a serious error',
    'Are you in Boston? — YES / SELECT ANOTHER',
    'Hi Carol, thanks for signing up! My name is Kevin...',
    'We use cookies on this website to make your browsing experience better',
    'By using the site you agree to our use of cookies',
    '[ ] Don\'t show this again',
    'This page requires you to use a recent browser (Internet Explorer 5+ or Netscape Navigator 7.0)' ],
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
