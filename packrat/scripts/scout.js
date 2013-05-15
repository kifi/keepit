// @match /^https?:\/\/[^\/]*\/.*/
// @require scripts/api.js
// loaded on every page, so no more dependencies

function logEvent() {  // parameters defined in main.js
  api.port.emit("log_event", Array.prototype.slice.call(arguments));
}

window.onerror = function (message, url, lineNo) {
  if (!/https?\:/.test(url)) {
    // this is probably from extension code, not from the website we're running this on
    api.port.emit("report_error", { message: message, url: url, lineNo: lineNo });
  }
};

var injected, t0 = +new Date, tile, paneHistory;

!function() {
  api.log("[scout]", location.hostname);
  var tileCount, onScroll;
  api.port.on({
    new_notification: function(n) {
      if (n.state != "visited" &&
          (!paneHistory || (paneHistory[0] != n.details.locator && paneHistory[0] != "/notices"))) {
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
        insertTile(o.hide, o.position);
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
        n += counts[i];
      }
      if (n) {
        tileCount.textContent = n;
        tile.appendChild(tileCount);
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

  document.addEventListener("keydown", onKeyDown, true);
  function onKeyDown(e) {
    if ((e.metaKey || e.ctrlKey) && e.shiftKey) {  // ⌘-shift-[key], ctrl-shift-[key]
      switch (e.keyCode) {
      case 75: // k
        if (tile && tile.dataset.kept) {
          api.port.emit("unkeep");
          delete tile.dataset.kept;
        } else {
          api.port.emit("keep", {url: document.URL, title: document.title, how: "public"});
          if (tile) tile.dataset.kept = "public";
        }
        break;
      case 76: // l
        api.port.emit("api:reload");
        break;
      case 77: // m
        keeper("togglePane", "key", "/messages");
        break;
      case 79: // o
        keeper("togglePane", "key", "/general");
        break;
      }
    }
  }

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

  function insertTile(hide, pos) {
    while (tile = document.getElementById("kifi-tile")) {
      tile.parentNode.removeChild(tile);
    }
    tile = document.createElement("div");
    tile.id = "kifi-tile";
    tile.style.display = "none";
    if (pos) {
      tile.style.top = pos.top >= 0 ? pos.top + "px" : "auto";
      tile.style.bottom = pos.bottom >= 0 ? pos.bottom + "px" : "auto";
      tile.dataset.pos = JSON.stringify(pos);
      positionTile(pos);
    }
    tile.innerHTML = "<div class=kifi-tile-transparent style='background-image:url(" + api.url("images/metro/tile_logo.png") + ")'></div>";
    tileCount = document.createElement("span");
    tileCount.className = "kifi-count";
    document.documentElement.appendChild(tile);
    tile.addEventListener("mouseover", function(e) {
      if (e.target === this ||
          e.target.parentNode === this && (e.target.classList.contains("kifi-tile-transparent") || e.target.classList.contains("kifi-count"))) {
        keeper("show", "tile");
      }
    });
    tile["kifi:position"] = positionTile;
    window.addEventListener("resize", onResize);
    api.require("styles/metro/tile.css", function() {
      if (!hide) {
        tile.style.display = "";
      }
    });
  }

  function onResize() {
    if (paneHistory) return;
    clearTimeout(onResize.t);  // throttling tile repositioning
    onResize.t = setTimeout(positionTile, 50);
  }

  function positionTile(pos) { // goal: as close to target position as possible while still in window
    pos = pos || JSON.parse(tile.dataset.pos || 0);
    if (!pos) return;
    var maxPos = window.innerHeight - 54;  // height (42) + margin-top (6) + margin-bottom (6)
    if (pos.bottom >= 0) {
      setTileVertOffset(pos.bottom - Math.max(0, Math.min(pos.bottom, maxPos)));
    } else if (pos.top >= 0) {
      setTileVertOffset(Math.max(0, Math.min(pos.top, maxPos)) - pos.top);
    }
  }

  function setTileVertOffset(px) {
    api.log("[setTileVertOffset] px:", px);
    tile.style["transform" in tile.style ? "transform" : "webkitTransform"] = "translate(0," + px + "px)";
  }

  setTimeout(function checkIfUseful() {
    if (document.hasFocus() && document.body.scrollTop > 300) {
      logEvent("slider", "usefulPage", {url: document.URL});
    } else {
      setTimeout(checkIfUseful, 5000);
    }
  }, 60000);

  api.onEnd.push(function() {
    document.removeEventListener("keydown", onKeyDown, true);
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
