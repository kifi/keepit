// @match /^https?:\/\/.*$/
// @require scripts/api.js
// loaded on every page, so no more dependencies

function logEvent() {  // parameters defined in main.js
  api.port.emit("log_event", Array.prototype.slice.call(arguments));
}

var session,
  pageData = {};
var tile = tile || function() {  // idempotent for Chrome
  'use strict';
  log("[keeper_scout]", location.hostname)();

  window.onerror = function(message, url, lineNo) {
    if (!/https?\:/.test(url)) {  // this is probably from extension code, not from the website we're running this on
      api.port.emit("report_error", { message: message, url: url, lineNo: lineNo });
    }
  };

  var whenSessionKnown = [], tileCard, tileCount, onScroll;
  api.port.emit("session", onSessionChange);
  api.port.on({
    session_change: onSessionChange,
    open_to: function(o) {
      keeper("showPane", o.trigger, o.locator, o.redirected);
    },
    button_click: keeper.bind(null, "togglePane", "button"),
    auto_show: keeper.bind(null, "show", "auto"),
    init: function(o) {
      pageData = o;
      var pos = o.position;
      if (pos) {
        tile.style.top = pos.top >= 0 ? pos.top + "px" : "auto";
        tile.style.bottom = pos.bottom >= 0 ? pos.bottom + "px" : "auto";
        pageData.pos = JSON.stringify(pos);
        positionTile(pos);
      }
      tileCard.classList.add("kifi-0s");
      if (o.kept) {
        pageData.kept = o.kept;
      } else {
        tile.removeAttribute("data-kept");
      }
      window.addEventListener("resize", onResize);
      api.require(["styles/insulate.css", "styles/keeper/tile.css"], function() {
        if (!o.hide) {
          tile.style.display = "";
        }
        tile.offsetHeight;
        tileCard.classList.remove("kifi-0s");
      });
    },
    kept: function(o) {
      if (o.kept) {
        pageData.kept = o.kept;
      } else {
        tile.removeAttribute("data-kept");
      }
    },
    keepers: function(o) {
      setTimeout(keeper.bind(null, "showKeepers", o.keepers, o.otherKeeps), 3000);
    },
    counts: function(counts) {
      tile && updateCounts(counts);
    },
    scroll_rule: function(r) {
      if (!onScroll && !window.slider2) {
        var lastScrollTime = 0;
        document.addEventListener("scroll", onScroll = function(e) {
          var t = e.timeStamp || Date.now();
          if (t - lastScrollTime > 100) {  // throttling to avoid measuring DOM too freq
            lastScrollTime = t;
            var hPage = document.body.scrollHeight;
            var hViewport = document[document.compatMode === "CSS1Compat" ? "documentElement" : "body"].clientHeight;
            var hSeen = window.pageYOffset + hViewport;
            log("[onScroll]", Math.round(hSeen / hPage * 10000) / 100, ">", r[1], "% and", hPage, ">", r[0] * hViewport, "?")();
            if (hPage > r[0] * hViewport && hSeen > (r[1] / 100) * hPage) {
              log("[onScroll] showing")();
              keeper("show", "scroll");
            }
          }
        });
      }
    },
    reset: cleanUpDom.bind(null, true)
  });
  function onKeyDown(e) {
    if ((e.metaKey || e.ctrlKey) && e.shiftKey) {  // ⌘-shift-[key], ctrl-shift-[key]
      // intentionally ommited altKey
      switch (e.keyCode) {
      case 75: // k
        if (session === undefined) {  // not yet initialized
          whenSessionKnown.push(onKeyDown.bind(this, e));
        } else if (!session) {
          toggleLoginDialog();
        } else if (pageData.kept) {
          api.port.emit("unkeep", withUrls({}));
          tile.removeAttribute("data-kept");  // delete .dataset.kept fails in FF 21
        } else {
          api.port.emit("keep", withUrls({title: document.title, how: "public"}));
          pageData.kept = "public";
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
        keeper("togglePane", "key", "/notices");
        e.preventDefault();
        break;
      }
    }
  }

  function updateCounts(counts) {
    var n = Math.max(counts.m, counts.n);
    if (n) {
      tileCount.textContent = n;
      tile.insertBefore(tileCount, tileCard.nextSibling);
    } else if (tileCount.parentNode) {
      tileCount.remove();
    }
    pageData.counts = JSON.stringify(counts);
  }

  function toggleLoginDialog() {
    api.require("scripts/dialog.js", function() {
      kifiDialog.toggleLoginDialog();
    });
  }

  function keeper() {  // gateway to slider2.js
    var args = Array.prototype.slice.apply(arguments);
    if (session === undefined) {  // not yet initialized
      args.unshift(null);
      whenSessionKnown.push(Function.bind.apply(keeper, args));
    } else if (!session) {
      toggleLoginDialog();
    } else {
      api.require("scripts/slider2.js", function() {
        if (onScroll && name != "showKeepers") {
          document.removeEventListener("scroll", onScroll);
          onScroll = null;
        }
        slider2[args.shift()].apply(slider2, args);
      });
    }
  }

  function onSessionChange(s) {
    if ((session = s)) {
      attachTile();
    } else {
      cleanUpDom();
    }
    while (whenSessionKnown.length) whenSessionKnown.shift()();
  }

  function attachTile() {
    if (tile && !tile.parentNode) {
      (document.querySelector("body") || document.documentElement).appendChild(tile);
    }
  }

  while (tile = document.getElementById("kifi-tile")) {
    tile.remove();
  }
  tile = document.createElement("kifi");
  tile.id = "kifi-tile";
  tile.className = "kifi-root kifi-tile";
  tile.style.display = "none";
  pageData.t0 = Date.now();
  tile.innerHTML =
    "<div class=kifi-tile-card>" +
    "<div class=kifi-tile-keep></div>" +
    "<div class=kifi-tile-kept></div></div>";
  tile["kifi:position"] = positionTile;
  tile.addEventListener("mouseover", function(e) {
    if (e.target === tileCount || tileCard.contains(e.target)) {
      keeper("show", "tile");
    }
  });

  tileCard = tile.firstChild;
  tileCount = document.createElement("span");
  tileCount.className = "kifi-count";
  document.addEventListener("keydown", onKeyDown, true);

  function onResize() {
    if (document.documentElement.classList.contains("kifi-with-pane")) return;
    clearTimeout(onResize.t);  // throttling tile repositioning
    onResize.t = setTimeout(positionTile, 50);
  }

  function positionTile(pos) { // goal: as close to target position as possible while still in window
    pos = pos || JSON.parse(pageData.pos || 0);
    if (!pos) return;
    var maxPos = window.innerHeight - 54;  // height (42) + margin-top (6) + margin-bottom (6)
    if (pos.bottom >= 0) {
      setTileVertOffset(pos.bottom - Math.max(0, Math.min(pos.bottom, maxPos)));
    } else if (pos.top >= 0) {
      setTileVertOffset(Math.max(0, Math.min(pos.top, maxPos)) - pos.top);
    }
  }

  function setTileVertOffset(px) {
    log("[setTileVertOffset] px:", px)();
    tile.style["transform" in tile.style ? "transform" : "webkitTransform"] = "translate(0," + px + "px)";
  }

  function cleanUpDom(leaveTileInDoc) {
    window.removeEventListener("resize", onResize);
    if (onScroll) {
      document.removeEventListener("scroll", onScroll);
      onScroll = null;
    }
    if (tile) {
      if (leaveTileInDoc) {
        tile.style.display = "none";
      } else {
        tile.remove();
      }
    }
    if (window.slider2) {
      slider2.hidePane();
    }
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
    cleanUpDom();
    session = tile = tileCard = tileCount = null;
  });

  return tile;
}();

var linkedInProfileRe = /^https?:\/\/[a-z]{2,3}.linkedin.com\/profile\/view\?/;
function withUrls(o) {
  'use strict';
  o.url = document.URL;
  var el, cUrl = ~o.url.search(linkedInProfileRe) ?
    (el = document.querySelector('.public-profile>dd>:first-child')) && 'http://' + el.textContent :
    (el = document.querySelector('link[rel=canonical]')) && el.href;
  var gUrl = (el = document.querySelector('meta[property="og:url"]')) && el.content;
  if (cUrl && cUrl !== o.url) o.canonical = cUrl;
  if (gUrl && gUrl !== o.url && gUrl !== cUrl) o.og = gUrl;
  return o;
}
