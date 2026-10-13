(function () {
  "use strict";
  var toast = document.getElementById("dl-toast");
  var hideTimer = null;
  function showToast(message) {
    toast.textContent = message;
    toast.classList.add("show");
    if (hideTimer) clearTimeout(hideTimer);
    hideTimer = setTimeout(function () { toast.classList.remove("show"); }, 6000);
  }
  document.addEventListener("click", function (event) {
    var link = event.target && event.target.closest ? event.target.closest(".js-apk-dl") : null;
    if (!link) return;
    showToast("Opening the latest GitHub release APK. If it isn't there yet, no release has been published; see All releases.");
  });
})();
