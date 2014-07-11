// @match /^https?:\/\/.*$/
// @require scripts/api.js
// loaded on every page, so no more dependencies

var me, tags = [];
var tile = tile || function() {  // idempotent for Chrome
  'use strict';
  log('[keeper_scout]', location.hostname);

  var whenMeKnown = [], tileParent, tileObserver, tileCard, tileCount, onScroll;
  while ((tile = document.getElementById('kifi-tile'))) {
    tile.remove();
  }
  tile = document.createElement('kifi');
  if (!tile.style) {
    return tile;  // no kifi DOM in XML viewer
  }
  tile.id = 'kifi-tile';
  tile.className = 'kifi-tile kifi-root';
  tile.style.display = "none";
  tile.dataset.t0 = Date.now();
  tile.innerHTML =
    '<div class="kifi-tile-card">' +
    '<div class="kifi-tile-keep"></div>' +
    '<div class="kifi-tile-kept"></div></div>';
  tile["kifi:position"] = positionTile;
  tile.addEventListener('mouseover', function (e) {
    if ((e.target === tileCount || tileCard.contains(e.target)) && e.isTrusted !== false) {
      loadAndDo('keeper', 'show', 'tile');
    }
  });

  tileCard = tile.firstChild;
  tileCount = document.createElement("span");
  tileCount.className = 'kifi-count';

  document.addEventListener('keydown', onKeyDown, true);
  document.addEventListener(('mozHidden' in document ? 'moz' : 'webkit') + 'fullscreenchange', onFullScreenChange);

  api.port.emit('me', onMeChange);
  api.port.on({
    me_change: onMeChange,
    guide: loadAndDo.bind(null, 'guide', 'show'),
    open_to: loadAndDo.bind(null, 'pane', 'show'),
    button_click: loadAndDo.bind(null, 'pane', 'toggle', 'button'),
    auto_engage: loadAndDo.bind(null, 'keeper', 'engage', 'auto'),
    init: function(o) {
      var pos = o.position;
      if (pos) {
        tile.style.top = pos.top >= 0 ? pos.top + "px" : "auto";
        tile.style.bottom = pos.bottom >= 0 ? pos.bottom + "px" : "auto";
        tile.dataset.pos = JSON.stringify(pos);
        positionTile(pos);
      }
      tileCard.classList.add('kifi-0s');
      if (o.kept) {
        tile.dataset.kept = o.kept;
      } else {
        tile.removeAttribute('data-kept');
      }
      tags = o.tags || [];
      window.addEventListener('resize', onResize);
      api.require(['styles/insulate.css', 'styles/keeper/tile.css'], function() {
        if (!o.hide) {
          tile.style.display = '';
          if (o.showKeeperIntro && !/\.(?:kifi|google)\./.test(location.hostname) && document.hasFocus()) {
            setTimeout(api.require.bind(api, 'scripts/keeper_intro.js', api.noop), 5000);
          }
        }
        tile.offsetHeight;
        tileCard.classList.remove('kifi-0s');
      });
    },
    show_keeper: function(show) {
      tile.style.display = show ? '' : 'none';
    },
    kept: function(o) {
      if (o.kept) {
        tile.dataset.kept = o.kept;
      } else if (o.kept === null) {
        tile.removeAttribute('data-kept');
      }
      if (o.fail && !tile.querySelector('.kifi-keeper') && !tile.classList.contains('kifi-shake')) {
        var eventType = 'animationName' in tile.style ? 'animationend' : 'webkitAnimationEnd';
        tile.addEventListener(eventType, function end() {
          tile.removeEventListener(eventType, end);
          tile.classList.remove('kifi-shake');
        });
        tile.classList.add('kifi-shake');
      }
    },
    count: function(n) {
      tile && updateCount(n);
    },
    scroll_rule: function(r) {
      if (!onScroll && !window.keeper) {
        var lastScrollTime = 0;
        document.addEventListener('scroll', onScroll = function (e) {
          var t = e.timeStamp || Date.now();
          if (t - lastScrollTime > 100) {  // throttling to avoid measuring DOM too freq
            lastScrollTime = t;
            var srEl = scrollRoot();
            var hPage = srEl.scrollHeight;
            var hViewport = srEl.clientHeight;
            var hSeen = window.pageYOffset + hViewport;
            log('[onScroll]', Math.round(hSeen / hPage * 10000) / 100, '>', r[1], '% and', hPage, '>', r[0] * hViewport, '?');
            if (hPage > r[0] * hViewport && hSeen > (r[1] / 100) * hPage && e.isTrusted !== false) {
              log('[onScroll] showing');
              loadAndDo('keeper', 'engage', 'scroll', 'button');
            }
          }
        });
      }
    },
    reset: cleanUpDom.bind(null, true),
    silence: cleanUpDom.bind(null, true),
    unsilenced: api.require.bind(api, 'scripts/unsilenced.js', function () {
      showUnsilenced();
    })
  });

  var tLastK = 0;
  function onKeyDown(e) {
    if ((e.metaKey || e.ctrlKey) && e.shiftKey && e.isTrusted !== false) {  // ⌘-shift-[key], ctrl-shift-[key]; tolerating alt
      switch (e.keyCode) {
      case 75: // k
        var now = Date.now();
        if (now - tLastK > 400) {
          tLastK = now;
          if (me === undefined) {  // not yet initialized
            whenMeKnown.push(onKeyDown.bind(this, e));
          } else if (!me) {
            toggleLoginDialog();
          } else if (tile && tile.dataset.kept) {
            api.port.emit('unkeep', withUrls({}));
          } else {
            api.port.emit('keep', withUrls({title: authoredTitle(), how: 'public'}));
          }
          e.preventDefault();
        }
        break;
      case 76: // l
        api.port.emit('toggle_mode');
        // not claiming this key binding for all users, so no e.preventDefault()
        break;
      case 79: // o
        api.port.emit('unsilence');
        loadAndDo('pane', 'show', {trigger: 'key', locator: '/messages:all'});
        e.preventDefault();
        break;
      case 83: // s
        api.port.emit('unsilence');
        loadAndDo('pane', 'compose', 'key');
        e.preventDefault();
        break;
      case 49: case 50: case 51: case 52: // 1,2,3,4
        if (e.altKey) {
          api.port.emit('resume_guide', e.keyCode - 48);
          e.preventDefault();
        }
        break;
      }
    }
  }

  function onFullScreenChange(e) {
    if (document[e.type[0] === 'm' ? 'mozFullScreenElement' : 'webkitFullscreenElement']) {
      tile.setAttribute('kifi-fullscreen', '');
    } else {
      tile.removeAttribute('kifi-fullscreen');
    }
  }

  function updateCount(n) {
    if (n) {
      tileCount.textContent = n;
      tile.insertBefore(tileCount, tileCard.nextSibling);
    } else if (tileCount.parentNode) {
      tileCount.remove();
    }
    tile.dataset.count = n;
  }

  function toggleLoginDialog() {
    api.require('scripts/iframe_dialog.js', function() {
      api.port.emit('auth_info', function (info) {
        iframeDialog.toggle('login', info.origin, info.data);
      });
    });
  }

  function loadAndDo(name, methodName) {  // gateway to keeper.js or pane.js
    if (me === undefined) {  // not yet initialized
      var args = Array.prototype.slice.call(arguments);
      args.unshift(null);
      whenMeKnown.push(Function.bind.apply(loadAndDo, args));
    } else if (!me) {
      toggleLoginDialog();
    } else {
      var args = Array.prototype.slice.call(arguments, 2);
      api.require('scripts/' + name + '.js', function() {
        if (onScroll && methodName !== 'showKeepers') {
          document.removeEventListener('scroll', onScroll);
          onScroll = null;
        }
        if (window.hideKeeperCallout) {
          hideKeeperCallout();
        }
        var o = window[name];
        o[methodName].apply(o, args);
      });
    }
  }

  function onMeChange(newMe) {
    if ((me = newMe)) {
      attachTile();
    } else {
      cleanUpDom();
    }
    while (whenMeKnown.length) whenMeKnown.shift()();
  }

  function attachTile() {
    if (!tile) return;
    if (!document.contains(tile) || tile.parentNode !== tileParent) {
      var parent = document.querySelector('body') || document.documentElement; // page can replace body
      parent.appendChild(tile);
      if (parent !== tileParent) {
        if (tileObserver) tileObserver.disconnect();
        tileObserver = new MutationObserver(attachTile);
        var what = {childList: true};
        for (var node = parent; node !== document; node = node.parentNode) {
          tileObserver.observe(node, what);
        }
        tileParent = parent;
      }
    } else {  // keep last
      var child = tileParent.lastElementChild;
      if (child !== tile && !document.documentElement.hasAttribute('kifi-pane-parent')) {
        while (child !== tile) {
          if (!child.classList.contains('kifi-root') && !child.tagName.toLowerCase() === 'script') {
            tileParent.appendChild(tile);
            break;
          }
          child = child.previousElementSibling;
        }
      }
    }
  }

  function onResize() {
    if (paneCall('showing')) return;
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
    log('[setTileVertOffset] px:', px);
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
        tile.style.display = 'none';
      } else {
        if (tileObserver) tileObserver.disconnect();
        tileObserver = tileParent = null;
        tile.remove();
      }
    }
    paneCall('hide');
  }

  function paneCall(methodName) {
    var pane = window.pane;
    return pane && pane[methodName]();
  }

  function scrollRoot() {
    return document[document.compatMode === 'CSS1Compat' ? 'documentElement' : 'body'];
  }

  api.onEnd.push(function() {
    document.removeEventListener('keydown', onKeyDown, true);
    cleanUpDom();
    me = tile = tileCard = tileCount = null;
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

function authoredTitle() {
  var el = document.querySelector('meta[property="og:title"]');
  var title = el && el.content.trim() || document.title.trim();
  if (title && !el) {
    el = document.body.firstElementChild;
    if (el && el.tagName === 'IMG' && el.src === document.URL) {
      title = '';  // discard browser-generated title for image file
    }
  }
  return title;
}
