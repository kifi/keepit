
!function() {
  var deepLink = $('html').data('kifiDeepLink');
  chrome.extension.sendMessage({type: "add_deep_link_listener", link: deepLink});
  if(deepLink.uri) {
    window.location = deepLink.uri;
  }
}();
