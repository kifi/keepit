// @match /^https?:\/\/[^\/]*\/.*/
// @require scripts/api.js

function logEvent() {  // parameters defined in main.js
  api.port.emit("log_event", Array.prototype.slice.call(arguments));
}

var injected, t0 = +new Date;

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
    show_notification: function(data) {
      api.require("scripts/notifier.js", function() {
        notifier.show(data);
      });
    },
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
    api.require("scripts/slider.js", callback);
  }

  function withSlider2(callback) {
    document.removeEventListener("scroll", onScrollMaybeShow);
    api.require("scripts/slider2.js", callback);
  }

  function insertTile(o) {
    var el;
    while (el = document.getElementById("kifi-tile")) {
      el.remove();
    }

    api.require("styles/metro/tile.css", function() {
      var tileEl = document.createElement("div");
      tileEl.id = "kifi-tile";
      tileEl.style.backgroundImage = "url(" + api.url("images/metro/tile_logo.png") + ")";
      if (o.kept) {
        tileEl.className = "kifi-kept";
      }
      var nUnread = (o.unreadComments || 0) + (o.unreadMessages || 0);
      var nTot = (o.numComments || 0) + (o.numMessages || 0);
      if (nUnread || nTot) {
        var countEl = document.createElement("span");
        countEl.className = "kifi-count" + (nUnread ? " kifi-unread" : "");
        countEl.textContent = nUnread || nTot;
        tileEl.appendChild(countEl);
      }
      document.documentElement.appendChild(tileEl);
      tileEl.addEventListener("mouseover", function() {
        withSlider2(function() {
          slider2.show(info, "tile");
        });
      });

      if (o.keepers && !o.kept) {
        withSlider2(function() {
          setTimeout(slider2.showKeepersFor.bind(slider2, o, !!countEl, 2000), 3000);
        });
      }
    });
  }
}();
