function log(message) {
  console.log.apply(console, Array.prototype.concat.apply(["[kifi][" + new Date().getTime() + "] "], arguments));
}

function logEvent(eventFamily, eventName, metaData, prevEvents) {
  var request = {
    type: "log_event",
    eventFamily: eventFamily,
    eventName: eventName,
    metaData: metaData || {},
    prevEvents: prevEvents || []
  }
  chrome.extension.sendRequest(request, function() {
    log("[logEvent] Event logged.")
  })
}

!function() {
  log("host: ", document.location.host);
  if (window !== top) {
    log("should run only on top window");
    return;
  }

  chrome.extension.sendRequest({type: "page_load"}, function(sliderDelayMs) {
    setTimeout(function() {
      log("showing slider");
      // We don't slide in automatically if the slider has already been shown (manually).
      chrome.extension.sendRequest({type: "check_hover_existed", kifi_hover: window.kifi_hover || false});
    }, sliderDelayMs);
  });
}();
