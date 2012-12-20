
!function() {
  document.querySelector(".kifi-deep-link-no-extension").style.display = "none";
  var deepLink = JSON.parse(document.documentElement.dataset.kifiDeepLink);
  chrome.extension.sendMessage({type: "add_deep_link_listener", link: deepLink});
  if (deepLink.uri) {
    window.location = deepLink.uri;
  }
}();
