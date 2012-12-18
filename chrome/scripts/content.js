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
        logEvent("slider","sliderOpenedByDelay",{"delay":sliderDelayMs});
        slider.show();
      }
    }, sliderDelayMs);
  });

  var t0 = new Date().getTime();
  chrome.extension.onRequest.addListener(function(request, sender, sendResponse) {
    if (request.type === "button_click") {
      if(slider.isShowing) {
        logEvent("slider","sliderClosedByIcon",{"delay":(new Date().getTime() - t0)});
      }
      else {
        logEvent("slider","sliderOpenedByIcon",{"delay":(new Date().getTime() - t0)});
      }
      slider.toggle();
    }
  });
  key('command+shift+k, ctrl+shift+k', function() {
    if(slider.isShowing) {
      logEvent("slider","sliderClosedByKeyShortcut",{"delay":(new Date().getTime() - t0)});
    }
    else {
      logEvent("slider","sliderOpenedByKeyShortcut",{"delay":(new Date().getTime() - t0)});
    }
    slider.toggle();
    return false;
  });
}();
