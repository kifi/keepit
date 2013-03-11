// @match /^https?:\/\/[^\/]*\/.*/
// @require scripts/api.js

function logEvent() {  // parameters defined in main.js
  api.port.emit("log_event", Array.prototype.slice.call(arguments));
}

var slider, slider2, injected, t0 = +new Date;

!function() {
  api.log("host:", location.hostname);
  var viewportEl = document[document.compatMode === "CSS1Compat" ? "documentElement" : "body"], rules = 0;

  document.addEventListener("keydown", function(e) {
    if (e.shiftKey && (e.metaKey || e.ctrlKey) && e.keyCode == 75 /*&& !rules.metro*/) {  // cmd-shift-K or ctrl-shift-K
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
      var hViewport = viewportEl.clientHeight;
      var hSeen = window.pageYOffset + hViewport;
      api.log("[onScrollMaybeShow]", Math.round(hSeen / hPage * 10000) / 100, ">", rules.scroll[1], "% and",
        hPage, ">", rules.scroll[0] * hViewport, "?");
      if (hPage > rules.scroll[0] * hViewport && hSeen > (rules.scroll[1] / 100) * hPage) {
        api.log("[onScrollMaybeShow] showing");
        autoShow("scroll");
      }
    }
  }

  setTimeout(function checkIfUseful() {
    if (document.hasFocus() && document.body.scrollTop > 300) {
      logEvent("slider", "usefulPage", {url: document.URL});
    } else {
      setTimeout(checkIfUseful, 5000);
    }
  }, 60000);

  api.port.on({
    button_click: function() {
      // if (rules.metro) {
      //   withSlider2(function() {
      //     slider2.toggle("button");
      //   });
      // } else {
        withSlider(function() {
         slider.toggle("button");
        });
      // }
    },
    auto_show: autoShow.bind(null, "auto"),
    slider_rules: function(sliderRules) {
      rules = sliderRules;
      if (rules.scroll) {
        document.addEventListener("scroll", onScrollMaybeShow);
      }
      if (rules.metro) {
        insertTile();
      }
    },
    open_slider_to: function(data) {
      // if (!rules.metro) {
        withSlider(function() {
          slider.shown() || slider.show(data.trigger, data.locator);
        });
      // }
    }});
  api.port.emit("get_slider_rules");

  function autoShow(trigger) {
    var width;
    if (rules.viewport && (width = viewportEl.clientWidth) < rules.viewport[0]) {
      api.log("[autoShow] viewport too narrow:", width, "<", rules.viewport[0]);
    // } else if (rules.metro) {
    //   withSlider2(function() {
    //     slider2.shown() || slider2.show(trigger);
    //   });
    } else {
      withSlider(function() {
        slider.shown() || slider.show(trigger);
      });
    }
  }

  function withSlider(callback) {
    document.removeEventListener("scroll", onScrollMaybeShow);
    if (slider) {
      callback();
    } else {
      api.require("scripts/slider.js", callback);
    }
  }

  function withSlider2(callback) {
    document.removeEventListener("scroll", onScrollMaybeShow);
    if (slider2) {
      callback();
    } else {
      api.require("scripts/slider2.js", callback);
    }
  }

  function insertTile() {
    var el;
    while (el = document.getElementById("kifi-tile")) {
      el.remove();
    }

    // don't want to inject a stylesheet until we show slider
    el = document.createElement("div");
    el.id = "kifi-tile";
    el.style.position = "fixed";
    el.style.zIndex = 2147483639;
    el.style.bottom = "10px";
    el.style.right = "10px";
    el.style.width = "42px";
    el.style.height = "42px";
    el.style.borderRadius = "5px";
    el.style.backgroundColor = "#000";
    el.style.color = "#fff";
    el.style.opacity = .2;
    el.style.font = "17px/42px sans-serif";
    el.style.textAlign = "center";
    el.style.cursor = "pointer";
    el.innerHTML = "kifi";
    document.documentElement.appendChild(el);
    el.addEventListener("mouseover", function() {
      withSlider2(function() {
        slider2.show("tile");
      });
    });
  }
}();
