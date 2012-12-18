function log(message) {
  console.log.apply(console, Array.prototype.concat.apply(["[kifi][" + new Date().getTime() + "] "], arguments));
}

function logEvent() {  // parameters defined in background.js
  chrome.extension.sendRequest({
    type: "log_event",
    args: Array.prototype.slice.apply(arguments)});
}

t0 = new Date().getTime();  // page load time

!function() {
  log("host: ", document.location.host);
  if (window !== top) {
    log("should run only on top window");
    return;
  }

  chrome.extension.sendRequest({type: "page_load"}, function(sliderDelayMs) {
    log("slider delay:", sliderDelayMs);
    setTimeout(function() {
      log("slider delay elapsed");
      if (!slider.shown()) {
        slider.show("auto");
      }
    }, sliderDelayMs);
  });

  chrome.extension.onRequest.addListener(function(request, sender, sendResponse) {
    if (request.type === "button_click") {
      slider.toggle("icon");
    }
  });
  key('command+shift+k, ctrl+shift+k', function() {
    slider.toggle("key");
    return false;
  });
}();
