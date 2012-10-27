
(function() {
  function log(message) {
    console.log("[" + new Date().getTime() + "] ", message);
  }

  log("FT content script starting");
  if (window.top !== window) {
    log("should run only on top window");
    return;
  }
  var href = document.location.href;
  if (href.indexOf("chrome-devtools://") >=0) return;
  var host = document.location.host;
  var isGoogle = (host == "www.google.com") ||  (host == "www.google.co.il");
  log("keepit: location is " + host + " isGoogle = " + isGoogle);
  if (isGoogle) {
    log("this script should not run in a google page!");
    return;
  } else {
    chrome.extension.sendRequest({
      type: "init_page", 
      location: document.location.href,
      isGoogle: false
      }, function(response) {
        log("[" + new Date().getTime() + "] init page response");
        log(response);      
      }
    );
  }
})();
