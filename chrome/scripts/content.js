function log(message) {
  console.log.apply(console, Array.prototype.concat.apply(["[kifi][" + new Date().getTime() + "] "], arguments));
}

function logEvent(eventFamily, eventName, metaData, prevEvents) {
  chrome.extension.sendRequest({
    type: "log_event",
    args: Array.prototype.slice.apply(arguments)});
}

!function() {
  log("host: ", document.location.host);
  if (window !== top) {
    log("should run only on top window");
    return;
  }

  chrome.extension.sendRequest({type: "page_load"}, function(sliderDelayMs) {
    log("slider delay:", sliderDelayMs);
    setTimeout(function() {
      log("slider delay has passed");
      if (!slider.alreadyShown) {
        slider.show();
      }
    }, sliderDelayMs);
  });

  chrome.extension.onRequest.addListener(function(request, sender, sendResponse) {
    if (request.type === "button_click") {
      slider.toggle();
    }
  });

  key('command+shift+k, ctrl+shift+k', function() {
    slider.toggle();
    return false;
  });
}();
