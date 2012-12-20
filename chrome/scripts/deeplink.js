
!function() {
  var deepLink = $('html').data('kifi-deep-link');
  chrome.extension.sendMessage({type: "add_deep_link_listener", link: deepLink});
  if(deepLink.uri) {
    window.location = deepLink.uri;
  }
}();