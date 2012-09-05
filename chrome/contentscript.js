
(function() {
  if (window.top !== window) {
    console.log("should run only on top window");
    return;
  }
  if (document.location.href.indexOf("chrome-devtools://") >=0) return;
  var isGoogle = (document.location.host == "www.google.com") ||  (document.location.host == "www.google.co.il")
  console.log("keepit: location is " + document.location.host + " isGoogle = " + isGoogle);

  chrome.extension.sendRequest({
    type: "init_page", 
    location: document.location.href,
    isGoogle: isGoogle
    }, function(response) {
      console.log("init page response: " + response);
    }
  );

})();
