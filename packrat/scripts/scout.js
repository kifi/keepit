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

var t0 = +new Date, tile, paneHistory, root = document.querySelector("body") || document.documentElement;

!function() {
  api.log("[scout]", location.hostname);
  var tileCard, tileCount, onScroll;
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
    init: function(o) {
      var pos = o.position;
      if (pos) {
        tile.style.top = pos.top >= 0 ? pos.top + "px" : "auto";
        tile.style.bottom = pos.bottom >= 0 ? pos.bottom + "px" : "auto";
        tile.dataset.pos = JSON.stringify(pos);
        positionTile(pos);
      }
      if (o.kept) {
        tile.dataset.kept = o.kept;
      }
      window.addEventListener("resize", onResize);
      api.require("styles/metro/tile.css", function() {
        if (!o.hide) {
          tile.style.display = "";
        }
      });
    },
    kept: function(o) {
      if (o.kept) {
        tile.dataset.kept = o.kept;
      } else {
        tile.removeAttribute("data-kept");
      }
    },
    keepers: function(o) {
      setTimeout(keeper.bind(null, "showKeepers", o.keepers, o.otherKeeps), 3000);
    },
    counts: function(counts) {
      var n = 0;
      for (var i in counts) {
        n += counts[i];
      }
      if (n) {
        tileCount.textContent = n;
        tile.insertBefore(tileCount, tileCard.nextSibling);
      } else if (tileCount.parentNode) {
        tile.removeChild(tileCount);
      }
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
          tile.removeAttribute("data-kept");  // delete .dataset.kept fails in FF 21
        } else {
          api.port.emit("keep", {url: document.URL, title: document.title, how: "public"});
          if (tile) tile.dataset.kept = "public";
        }
        e.preventDefault();
        break;
      case 76: // l
        api.port.emit("api:reload");
        e.preventDefault();
        break;
      case 77: // m
        keeper("togglePane", "key", "/messages");
        e.preventDefault();
        break;
      case 79: // o
        keeper("togglePane", "key", "/general");
        e.preventDefault();
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

  !function insertTile() {
    while (tile = document.getElementById("kifi-tile")) {
      tile.parentNode.removeChild(tile);
    }
    tile = document.createElement("div");
    tile.id = tile.className = "kifi-tile";
    tile.style.display = "none";
    tile.innerHTML =
      "<div class=kifi-tile-card>" +
      "<div class=kifi-tile-keep style='background-image:url(" + api.url("images/metro/tile_logo.png") + ")'></div>" +
      "<div class=kifi-tile-kept></div></div>";
    tileCard = tile.firstChild;
    tileCount = document.createElement("span");
    tileCount.className = "kifi-count";
    root.appendChild(tile);
    tile.addEventListener("mouseover", function(e) {
      if (e.target === tileCount || tileCard.contains(e.target)) {
        keeper("show", "tile");
      }
    });
    tile["kifi:position"] = positionTile;
  }();

  function onResize() {
    if (paneHistory) return;
    clearTimeout(onResize.t);  // throttling tile repositioning
    onResize.t = setTimeout(positionTile, 50);
  }

  function positionTile(pos) { // goal: as close to target position as possible while still in window
    pos = pos || JSON.parse(tile && tile.dataset.pos || 0);
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
