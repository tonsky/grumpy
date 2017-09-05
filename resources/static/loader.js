var loader_status = "IDLE",
    loader = document.querySelector(".loader");


function load_posts() {
  loader_status = "LOADING";
  loader.classList.add("loader-loading");
  loader.classList.remove("loader-error");
  loader.querySelector("img").removeEventListener("click", load_posts);
  var posts = document.querySelectorAll(".post"),
      last_post = posts[posts.length - 1],
      last_post_id = last_post.getAttribute("data-id");

  var req = new XMLHttpRequest();

  req.addEventListener("load", function() {
    loader.classList.remove("loader-loading");
    var resp = this;
    if (resp.status !== 200) {
      loader_status = "ERROR";
      loader.classList.add("loader-error");
      loader.querySelector("img").addEventListener("click", load_posts);
    } else if (resp.responseText.length === 0) {
      loader_status = "DONE";
      loader.remove();
    } else {
      var div = document.createElement("div");
      div.innerHTML = resp.responseText;
      loader.parentNode.insertBefore(div, loader);
      loader_status = "IDLE";
    }
  });

  req.open("GET", '/after/' + last_post_id);
  req.send();
}


function on_scroll(e) {
  var cont            = document.body,
      full_height     = cont.scrollHeight,
      viewport_height = window.innerHeight,
      scrolled        = window.pageYOffset || document.documentElement.scrollTop || document.body.scrollTop,
      scrolled_bottom = scrolled + viewport_height,
      at_bottom       = scrolled_bottom >= full_height - 200;

  if ( at_bottom && loader_status === "IDLE" )
    load_posts();
}


window.addEventListener("load", function() {
  on_scroll();
  window.addEventListener("scroll", on_scroll);
});