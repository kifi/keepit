
(function() {
  if (window.top !== window) {
    console.log("should run only on top window");
    return;
  }
  var isGoogle = (document.location.host == "www.google.com");
  console.log("keepit: location is " + document.location.host + " isGoogle = " + isGoogle);

  chrome.extension.sendRequest({type: "init_page", isGoogle: isGoogle}, function(response) {});

})();