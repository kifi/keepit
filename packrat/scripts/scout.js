// @match /^https?:\/\/[^\/]*\/.*/
// @require scripts/api.js

function logEvent() {  // parameters defined in main.js
  api.port.emit("log_event", Array.prototype.slice.call(arguments));
}

var injected, t0 = +new Date, tile, paneHistory;

!function() {
  api.log("[scout]", location.hostname);
  var tileCount, onScroll;

  api.port.on({
    show_notification: function(n) {
      if (!paneHistory || paneHistory[0] != n.details.locator) {
        api.require("scripts/notifier.js", function() {
          notifier.show(n);
        });
      }
    },
    open_to: function(o) {
      keeper("showPane", o.trigger, o.locator);
    },
    button_click: keeper.bind(null, "togglePane", "button"),
    auto_show: keeper.bind(null, "show", "auto"),
    kept: function(o) {
      if (!tile) {
        insertTile(o.hide);
      }
      if (o.kept) {
        tile.dataset.kept = o.kept;
      } else {
        delete tile.dataset.kept;
      }
    },
    keepers: function(o) {
      setTimeout(keeper.bind(null, "showKeepers", o.keepers, o.otherKeeps), 3000);
    },
    counts: function(counts) {
      if (!tileCount) return;
      var n = 0;
      for (var i in counts) {
        var c = counts[i];  // negative means unread
        n = (c < 0 ? Math.min(n, 0) : n) + (n < 0 ? Math.min(c, 0) : c);
      }
      if (n) {
        tileCount.textContent = Math.abs(n);
        tileCount.classList[n < 0 ? "add" : "remove"]("kifi-unread");
        (n < 0 ? tile : tile.firstChild).appendChild(tileCount);
      } else if (tileCount.parentNode) {
        tileCount.parentNode.removeChild(tileCount);
      }
      tile.classList[n ? "add" : "remove"]("kifi-with-count");
      tile.dataset.counts = JSON.stringify(counts);
    },
    scroll_rule: function(r) {
      if (!onScroll) {
        var lastScrollTime = 0;
        document.addEventListener("scroll", onScroll = function(e) {
          var t = e.timeStamp || +new Date;
          if (t - lastScrollTime > 100) {  // throttling to avoid measuring DOM too freq
            lastScrollTime = t;
            var hPage = document.body.scrollHeight;
            var hViewport = document[document.compatMode === "CSS1Compat" ? "documentElement" : "body"].clientHeight;
            var hSeen = window.pageYOffset + hViewport;
            api.log("[onScroll]", Math.round(hSeen / hPage * 10000) / 100, ">", r[1], "% and", hPage, ">", r[0] * hViewport, "?");
            if (hPage > r[0] * hViewport && hSeen > (r[1] / 100) * hPage) {
              api.log("[onScroll] showing");
              keeper("show", "scroll");
            }
          }
        });
      }
    }
  });

  document.addEventListener("keydown", function(e) {
    if ((e.metaKey || e.ctrlKey) && e.shiftKey) {
      switch (e.keyCode) {
      case 75: // ⌘-shift-k, ctrl-shift-k
        if (tile && tile.dataset.kept) {
          api.port.emit("unkeep");
          delete tile.dataset.kept;
        } else {
          api.port.emit("keep", {url: document.URL, title: document.title, how: "public"});
          if (tile) tile.dataset.kept = "public";
        }
        return false;
      case 79: // ⌘-shift-o, ctrl-shift-o
        keeper("togglePane", "key");
        return false;
      }
    }
  }, true);

  function keeper() {  // gateway to slider2.js
    var args = Array.prototype.slice.apply(arguments), name = args.shift();
    if (onScroll && name != "showKeepers") {
      document.removeEventListener("scroll", onScroll);
      onScroll = null;
    }
    api.require("scripts/slider2.js", function() {
      slider2[name].apply(slider2, args);
    });
  }

  function insertTile(hide) {
    while (tile = document.getElementById("kifi-tile")) {
      tile.parentNode.removeChild(tile);
    }
    tile = document.createElement("div");
    tile.id = "kifi-tile";
    tile.style.display = "none";
    tile.innerHTML = "<div class=kifi-tile-transparent style='background-image:url(" + api.url("images/metro/tile_logo.png") + ")'></div>";
    tileCount = document.createElement("span");
    tileCount.className = "kifi-count";
    document.documentElement.appendChild(tile);
    tile.addEventListener("mouseover", keeper.bind(null, "show", "tile"));
    api.require("styles/metro/tile.css", function() {
      if (!hide) {
        tile.style.display = "";
      }
    });
  }

  setTimeout(function checkIfUseful() {
    if (document.hasFocus() && document.body.scrollTop > 300) {
      logEvent("slider", "usefulPage", {url: document.URL});
    } else {
      setTimeout(checkIfUseful, 5000);
    }
  }, 60000);

  api.onEnd.push(function() {
    if (onScroll) {
      document.removeEventListener("scroll", onScroll);
      onScroll = null;
    }
    if (tile) {
      tile.parentNode.removeChild(tile);
      tile = tileCount = null;
    }
  });
}();
