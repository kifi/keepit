// @match /^https?:\/\/[^\/]*\/.*/
// @require scripts/api.js

function logEvent() {  // parameters defined in main.js
  api.port.emit("log_event", Array.prototype.slice.call(arguments));
}

var slider, injected, t0 = +new Date;

!function() {
  api.log("host:", location.host);

  document.addEventListener("keydown", function(e) {
    if (e.shiftKey && (e.metaKey || e.ctrlKey) && e.keyCode == 75) {  // cmd-shift-K or ctrl-shift-K
      withSlider(function() {
        slider.toggle("key");
      });
      return false;
    }
  });

  function onScrollMaybeShow(e) {
    var t = e.timeStamp || +new Date;
    if (t - (onScrollMaybeShow.t || 0) > 100) {  // throttling to avoid measuring DOM too freq
      onScrollMaybeShow.t = t;
      var hPage = document.body.scrollHeight;
      var hViewport = document[document.compatMode === "CSS1Compat" ? "documentElement" : "body"].clientHeight;
      var hSeen = window.pageYOffset + hViewport;
      if (hPage > 2 * hViewport && hSeen > .8 * hPage) {
        api.log("[onScrollMaybeShow] showing");
        withSlider(function() {
          slider.shown() || slider.show("scroll");
        });
      } else {
        api.log("[onScrollMaybeShow] seen:", hSeen, "/", hPage, "viewport:", hViewport);
      }
    }
  }

  setTimeout(function checkIfUseful() {
    if (document.hasFocus() && document.body.scrollTop > 300) {
      logEvent("slider", "usefulPage", {url: document.location.href});
    } else {
      setTimeout(checkIfUseful, 5000);
    }
  }, 60000);

  api.port.on({
    button_click: function() {
      withSlider(function() {
        slider.toggle("button");
      });
    },
    auto_show: function() {
      withSlider(function() {
        slider.shown() || slider.show("auto");
      });
    },
    auto_show_eligible: function() {
      document.addEventListener("scroll", onScrollMaybeShow);
    },
    deep_link: function(link) {
      withSlider(function() {
        slider.openDeepLink(link);
      });
    }});
  api.port.emit("check_auto_show_eligible");

  function withSlider(callback) {
    document.removeEventListener("scroll", onScrollMaybeShow);
    if (slider) {
      callback();
    } else {
      api.require("scripts/slider.js", callback);
    }
  }
}();
