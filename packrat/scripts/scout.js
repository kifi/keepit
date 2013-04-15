// @match /^https?:\/\/[^\/]*\/.*/
// @require scripts/api.js

function logEvent() {  // parameters defined in main.js
  api.port.emit("log_event", Array.prototype.slice.call(arguments));
}

var injected, t0 = +new Date;

!function() {
  api.log("host:", location.hostname);
  var viewportEl = document[document.compatMode === "CSS1Compat" ? "documentElement" : "body"];
  var info, openTo, rules = 0, tile, count;

  document.addEventListener("keydown", function(e) {
    if (e.shiftKey && (e.metaKey || e.ctrlKey) && e.keyCode == 75 && !info.metro) {  // cmd-shift-K or ctrl-shift-K
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
    show_notification: function(data) {
      api.require("scripts/notifier.js", function() {
        notifier.show(data);
      });
    },
    init_slider: function(o) {  // may be called multiple times due to in-page navigation (e.g. hashchange, popstate)
      info = o;
      if (openTo) {
        o.locator = openTo.locator;
        o.trigger = openTo.trigger;
        o.force = openTo.force;
        openTo = null;
      }
      rules = o.rules || 0;
      if (o.metro) {
        if (!tile) {
          insertTile(o);
        }
        updateCount(o.counts);
      }
      if (o.locator) {
        openSlider(o);
      } else if (rules.scroll) {
        document.addEventListener("scroll", onScrollMaybeShow);
      }
    },
    open_slider_to: function(o) {
      if (o.metro && !info) {
        openTo = o;
      } else {
        openSlider(o);
      }
    },
    button_click: function() {
      if (info.metro) {
        withSlider2(function() {
          slider2.toggle(info, "button");
        });
      } else {
        withSlider(function() {
          slider.toggle("button");
        });
      }
    },
    auto_show: autoShow.bind(null, "auto"),
    counts: updateCount});

  api.port.emit("init_slider_please");

  function autoShow(trigger) {
    var width;
    if (rules.viewport && !info.metro && (width = viewportEl.clientWidth) < rules.viewport[0]) {
      api.log("[autoShow] viewport too narrow:", width, "<", rules.viewport[0]);
    } else {
      openSlider({trigger: trigger});
    }
  }

  function openSlider(o) {
    if (info.metro) {
      withSlider2(function() {
        if (o.force) {
          slider2.openDeepLink(info, o.trigger, o.locator);
        } else {
          slider2.shown() || slider2.show(info, o.trigger, o.locator);
        }
      });
    } else {
      withSlider(function() {
        slider.shown() || slider.show(o.trigger, o.locator);
      });
    }
  }

  function withSlider(callback) {
    document.removeEventListener("scroll", onScrollMaybeShow);
    api.require("scripts/slider.js", callback);
  }

  function withSlider2(callback) {
    document.removeEventListener("scroll", onScrollMaybeShow);
    api.require("scripts/slider2.js", callback);
  }

  function insertTile(o) {
    while (tile = document.getElementById("kifi-tile")) {
      tile.parentNode.removeChild(tile);
    }
    tile = document.createElement("div");
    tile.id = "kifi-tile";
    tile.className = o.kept ? "kifi-kept" : "";
    tile.style.display = "none";
    tile.innerHTML = "<div class=kifi-tile-transparent style='background-image:url(" + api.url("images/metro/tile_logo.png") + ")'></div>";
    count = document.createElement("span");
    count.className = "kifi-count";
    document.documentElement.appendChild(tile);
    tile.addEventListener("mouseover", function() {
      withSlider2(function() {
        slider2.show(info, "tile");
      });
    });
    api.require("styles/metro/tile.css", function() {
      tile.style.display = "";
      if (o.keepers.length && !o.kept) {
        withSlider2(function() {
          setTimeout(slider2.showKeepersFor.bind(slider2, o, tile, 2000), 3000);
        });
      }
    });
  }

  function updateCount(counts) {
    if (!count) return;
    var n = 0;
    for (var i in counts) {
      var c = counts[i];  // negative means unread
      n = (c < 0 ? Math.min(n, 0) : n) + (n < 0 ? Math.min(c, 0) : c);
    }
    if (n) {
      count.textContent = Math.abs(n);
      count.classList[n < 0 ? "add" : "remove"]("kifi-unread");
      (n < 0 ? tile : tile.firstChild).appendChild(count);
    } else if (count.parentNode) {
      count.parentNode.removeChild(count);
    }
    tile.classList[n ? "add" : "remove"]("kifi-with-count");
  }
}();
