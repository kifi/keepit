
(function() {
  if (window.top !== window) {
    console.log("should run only on top window");
    return;
  }
  var href = document.location.href;
  if (href.indexOf("chrome-devtools://") >=0) return;
  var host = document.location.host;
  var isGoogle = (host == "www.google.com") ||  (host == "www.google.co.il");
  if (isGoogle) {
    var mngb = document.getElementById("mngb");
    if (!mngb) {
      console.log("google mngb is not there, forget it!");
      return;
    }
  }
  console.log("keepit: location is " + host + " isGoogle = " + isGoogle);

  chrome.extension.sendRequest({
    type: "init_page", 
    location: document.location.href,
    isGoogle: isGoogle
    }, function(response) {
      console.log("init page response: ");
      console.log(response);      
    }
  );

})();
