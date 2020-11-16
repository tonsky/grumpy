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
    'This page requires you to use a recent browser (Internet Explorer 5+ or Netscape Navigator 7.0)',
    'Like failed',
    'Add to Home Screen',
    'In high demand—6 other people are looking at this page',
    'You’ve read 9 stories this month. Let’s make things official',
    'Medium follows Do Not Track but we track to personalize your experience and send data to select third-parties to make our features work',
    'Plugin is ready to update',
    'Part 2 of 3: Installing features and drivers. 50% complete',
    'Don’t turn off your computer, this will take a while',
    'Данный ресурс заблокирован! по решению Роскомнадзора',
    'Already a member? Sign in.',
    'Trust this computer?',
    'Why am I seeing this?',
    'Drop images here to send them in a quick way',
    'Choose an account to continue',
    'The operation can’t be completed because it isn’t supported',
    'Are you sure you want to close all programs and shut down the computer?',
    'Please take a moment to rate your experience',
    'Would you like to save this file?',
    ',   ?',
    'This app is using significant energy',
    'The disk was not ejected properly',
    'Trying to regain internet connection... Hide this message.',
    'Loading...',
    'Remind me tomorrow',
    'Stop showing me this',
    'Why am I seeing this?',
    'See fewer posts like this',
    'This content is not available in your country',
    'This website wants to know your location',
    'We’re sorry! You need to update your Flash Player.',
    'You have one unread message',
    'We are updating our privacy policy',
    'To read the rest of the story, please buy a subscription'],
  subtitle_el,
  subtitle_idx = subtitles.length;

function shuffle(array) {
  for (var i = array.length - 1; i > 0; i--) {
    var j = Math.floor(Math.random() * (i + 1));
    var temp = array[i];
    array[i] = array[j];
    array[j] = temp;
  }
}

function reload_subtitle() {
  ++subtitle_idx;
  if (subtitle_idx >= subtitles.length) {
    shuffle(subtitles);
    subtitle_idx = 0;
  }
  subtitle_el.innerHTML = subtitles[subtitle_idx];
}

window.addEventListener("DOMContentLoaded", function() {
  subtitle_el = document.querySelector('.subtitle-text');
  if (subtitle_el) {
    subtitle_el.onclick = reload_subtitle;
    reload_subtitle();
    if (document.cookie.indexOf("grumpy_user=") >= 0) {
      document.body.classList.remove("anonymous");
    }
  }
});

var rotate_angle_deg = 0;

function body_rotate() {
  rotate_angle_deg += 180;
  document.body.style.transform = "rotate(" + rotate_angle_deg + "deg)";
}

function toggle_video(wrapper, play) {
  var video = wrapper.querySelector("video"),
      overlay = wrapper.querySelector(".post_video_overlay");
  if (play) {
    overlay.classList.remove("post_video_overlay-paused");
  } else if (video.paused) {
    video.play();
    overlay.classList.remove("post_video_overlay-paused");
  } else {
    video.pause();
    overlay.classList.add("post_video_overlay-paused");
  }
}
