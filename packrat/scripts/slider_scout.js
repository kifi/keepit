// @match /^https?:\/\/[^\/]*\/.*/
// @require scripts/api.js

function logEvent() {  // parameters defined in main.js
  api.port.emit("log_event", Array.prototype.slice.call(arguments));
}

var slider, slider2, injected, t0 = +new Date;

!function() {
  api.log("host:", location.hostname);
  var viewportEl = document[document.compatMode === "CSS1Compat" ? "documentElement" : "body"], info, rules = 0;

  document.addEventListener("keydown", function(e) {
    if (e.shiftKey && (e.metaKey || e.ctrlKey) && e.keyCode == 75 /*&& !info.metro*/) {  // cmd-shift-K or ctrl-shift-K
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
    init_slider: function(o) {
      info = o;
      rules = o.rules || 0;
      if (o.metro) {
        insertTile(o);
      }
      if (o.locator) {
        // if (o.metro) { ... } else
        withSlider(function() {
          slider.shown() || slider.show(o.trigger, o.locator);
        });
      } else if (rules.scroll) {
        document.addEventListener("scroll", onScrollMaybeShow);
      }
    },
    open_slider_to: function(data) {
      // if (info.metro) { ... } else
      withSlider(function() {
        slider.shown() || slider.show(data.trigger, data.locator);
      });
    },
    button_click: function() {
      withSlider(function() {
        slider.toggle("button");
      });
    },
    auto_show: autoShow.bind(null, "auto")});
  api.port.emit("init_slider_please");

  function autoShow(trigger) {
    var width;
    if (rules.viewport && (width = viewportEl.clientWidth) < rules.viewport[0]) {
      api.log("[autoShow] viewport too narrow:", width, "<", rules.viewport[0]);
    // } else if (info.metro) { ...
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

  function insertTile(o) {
    var el;
    while (el = document.getElementById("kifi-tile")) {
      el.remove();
    }

    // don't want to inject a stylesheet until we show slider
    el = document.createElement("div");
    el.id = "kifi-tile";
    el.style.position = "fixed";
    el.style.zIndex = 2147483639;
    el.style.bottom = "6px";
    el.style.right = "6px";
    el.style.left = "auto";
    el.style.top = "auto";
    el.style.width = "42px";
    el.style.height = "42px";
    el.style.borderRadius = "5px";
    el.style.color = "#fff";
    el.style.backgroundRepeat = "no-repeat";
    el.style.backgroundSize = "26px auto";
    el.style.cursor = "pointer";
    updateTile.call(el, o.kept);
    document.documentElement.appendChild(el);
    el.addEventListener("mouseover", function() {
      withSlider2(function() {
        slider2.show(info, "tile");
      });
    });
    el.addEventListener("kifi:keep", function(e) {
      api.log("[kifi:keep]", e);
    });

  }
}();

function updateTile(kept) {
  var el = this.id === "kifi-tile" ? this : document.getElementById("kifi-tile");
  el.style.backgroundColor = kept ? "#2980b9": "#000";
  el.style.backgroundImage = "url(" + api.url(kept ? "images/metro/tile_kept.png" : "images/metro/tile_logo.png") + ")";
  el.style.backgroundPosition = kept ? "9px 16px" : "9px 15px";
  el.style.opacity = kept ? .3 : .2;
}
