function log(message) {
  console.log.apply(console, Array.prototype.concat.apply(["[kifi][" + new Date().getTime() + "] "], arguments));
}

function logEvent() {  // parameters defined in background.js
  chrome.extension.sendMessage({
    type: "log_event",
    args: Array.prototype.slice.apply(arguments)});
}

var t0 = new Date().getTime();

!function() {
  log("host:", location.host);
  if (window !== top) {
    log("not in top window");
    return;
  }

  document.addEventListener("keydown", function(e) {
    if (e.shiftKey && (e.metaKey || e.ctrlKey) && e.keyCode == 75) {  // cmd-shift-K or ctrl-shift-K
      withSlider(function() {
        slider.toggle("key");
      });
      return false;
    }
  });

  chrome.extension.onMessage.addListener(function handleMessage(msg) {
    log("[onMessage] handling:", msg);
    switch (msg.type) {
      case "button_click":
        withSlider(function() {
          slider.toggle("button");
        });
        break;
      case "auto_show_after":
        setTimeout(function() {
          withSlider(function() {
            slider.shown() || slider.show("auto");
          });
        }, msg.ms);
        break;
    }
  });

  var injecting;
  function withSlider(callback) {
    if (window.slider) {
      callback();
    } else if (injecting) {
      // TODO: queue callback?
    } else {
      injecting = true;
      chrome.extension.sendMessage({type: "inject_slider"}, callback);
    }
  }
}();
