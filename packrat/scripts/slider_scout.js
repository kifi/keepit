// @match .*
// @require scripts/api.js

function logEvent() {  // parameters defined in main.js
  api.port.emit("log_event", arguments);
}

var slider, injected, t0 = new Date().getTime();

!function() {
  api.log("host:", location.host);
  if (window !== top) {
    api.log("not in top window");
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

  api.port.on({
    button_click: function() {
      withSlider(function() {
        slider.toggle("button");
      });
    },
    auto_show_after: function(ms) {
      setTimeout(function() {
        withSlider(function() {
          slider.shown() || slider.show("auto");
        });
      }, ms);
    },
    deep_link: function(link) {
      withSlider(function() {
        slider.openDeepLink(link);
      });
    }});

  function withSlider(callback) {
    if (slider) {
      callback();
    } else {
      api.port.emit("require", {injected: injected, scripts: ["scripts/slider.js"]}, callback);
    }
  }
}();
