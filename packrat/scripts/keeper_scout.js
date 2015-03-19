// @match /^https?:\/\/.*$/
// @require scripts/api.js
// loaded on every page, so no more dependencies

api.identify('keeperScout');

var k = k && k.kifi ? k : {kifi: true};

k.tile = k.tile || function () {  // idempotent for Chrome
  'use strict';
  log('[keeper_scout]', location.hostname);

  var whenMeKnown = [], tile, tileParent, tileObserver, tileCard;
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
  tile.innerHTML = '<div class="kifi-tile-card"></div><div class="kifi-tile-dot"></div>';
  tile["kifi:position"] = positionTile;
  tile.addEventListener('mouseover', function (e) {
    if ((tileCard.contains(e.target)) && e.isTrusted !== false) {
      loadAndDo('keeper', 'show');
    }
  });

  tileCard = tile.firstChild;

  document.addEventListener('keydown', onKeyDown, true);
  document.addEventListener(('mozHidden' in document ? 'moz' : 'webkit') + 'fullscreenchange', onFullScreenChange);

  api.port.emit('me', onMeChange);
  api.port.on({
    me_change: onMeChange,
    guide: loadAndDo.bind(null, 'guide', 'show'),
    show_pane: loadAndDo.bind(null, 'pane', 'show'),
    button_click: loadAndDo.bind(null, 'pane', 'toggle', 'icon'),
    compose: loadAndDo.bind(null, 'keeper', 'compose'),
    auto_engage: loadAndDo.bind(null, 'keeper', 'engage'),
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
      window.addEventListener('resize', onResize);
      onResize.bound = true;
      api.require(['styles/insulate.css', 'styles/keeper/tile.css'], function() {
        if (!o.hide) {
          tile.style.display = '';
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
        if ('duplicate' in o) {
          if (o.duplicate) {
            loadAndDo('keeper', 'showKeepBox', 'key');
          } else {
            tileCard.textContent = '';
            tileCard.clientHeight; // forces layout
            tileCard.addEventListener(animationend(), function end(e) {
              tileCard.removeEventListener(e.type, end);
              tileCard.textContent = '';
            });
            tileCard.innerHTML = o.kept === 'public' ?
              '<div class="kifi-tile-icon-keep"></div>' :
              '<div class="kifi-tile-icon-lock"></div>';
          }
        }
      } else if (o.kept === null) {
        tile.removeAttribute('data-kept');
      }
      if (o.fail && !tile.querySelector('.kifi-keeper') && !tile.classList.contains('kifi-shake')) {
        tile.addEventListener(animationend(), function end(e) {
          tile.removeEventListener(e.type, end);
          tile.classList.remove('kifi-shake');
        });
        tile.classList.add('kifi-shake');
      }
    },
    count: function(n) {
      tile && updateCount(n);
    },
    reset: cleanUpDom.bind(null, 'history'),
    silence: cleanUpDom.bind(null, 'silence'),
    unsilenced: api.require.bind(api, 'scripts/unsilenced.js', function () {
      showUnsilenced();
    })
  });

  var tLastK = 0;
  function onKeyDown(e) {
    if ((e.metaKey || e.ctrlKey) && e.shiftKey && e.isTrusted !== false) {  // ⌘-shift-[key], ctrl-shift-[key]; tolerating alt
      switch (e.keyCode) {
      case 75: // k
        var now = Date.now(), kept;
        if (now - tLastK > 400) {
          tLastK = now;
          if (!('me' in k)) {  // not yet initialized
            whenMeKnown.push(onKeyDown.bind(this, e));
          } else if (!k.me) {
            toggleLoginDialog();
          } else if (k.keepBox && k.keepBox.showing()) {
            k.keepBox.keep(e.altKey, e.guided);
          } else {
            api.port.emit('keep', withTitles(withUrls({secret: e.altKey, how: 'key'})));
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
        api.port.emit('pane?', function (loc) {
          loadAndDo('pane', 'show', {trigger: 'key', locator: loc});
        });
        e.preventDefault();
        break;
      case 83: // s
        api.port.emit('unsilence');
        loadAndDo('keeper', 'compose', 'key');
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
    tile.dataset.count = n;
  }

  function animationend() {
    return 'animationName' in tile.style ? 'animationend' : 'webkitAnimationEnd';
  }

  function toggleLoginDialog() {
    api.require('scripts/iframe_dialog.js', function() {
      api.port.emit('auth_info', function (info) {
        iframeDialog.toggle('login', info.origin, info.data);
      });
    });
  }

  function loadAndDo(name, methodName) {  // gateway to keeper.js or pane.js
    if (!('me' in k)) {  // not yet initialized
      var args = Array.prototype.slice.call(arguments);
      args.unshift(null);
      whenMeKnown.push(Function.bind.apply(loadAndDo, args));
    } else if (!k.me) {
      toggleLoginDialog();
    } else {
      var args = Array.prototype.slice.call(arguments, 2);
      api.require('scripts/' + name + '.js', function() {
        (k.hideKeeperCallout || api.noop)();
        var o = k[name];
        o[methodName].apply(o, args);
      });
    }
  }

  function onMeChange(newMe) {
    if ((k.me = newMe)) {
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
          if (!child.classList.contains('kifi-root') && !/^(?:script|style)$/i.test(child.tagName) && child.style.display !== 'none') {
            tileParent.appendChild(tile);
            break;
          }
          child = child.previousElementSibling;
        }
      }
    }
  }

  function onResize() {
    if (k.pane && k.pane.showing()) return;
    clearTimeout(onResize.t);  // throttling tile repositioning
    onResize.t = setTimeout(positionTile, 50);
  }

  function positionTile(pos) { // goal: as close to target position as possible while still in window
    pos = pos || JSON.parse(tile && tile.dataset.pos || 0);
    if (!pos) return;
    var maxPos = window.innerHeight - 62;
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

  function cleanUpDom(trigger) {
    if (onResize.bound) {  // crbug.com/405705
      window.removeEventListener('resize', onResize);
      onResize.bound = false;
    }
    if (tile) {
      if (trigger) {
        if (k.keeper) {
          k.keeper.hide(trigger);
        }
        tile.removeAttribute('data-kept');
      } else {
        if (tileObserver) tileObserver.disconnect();
        tileObserver = tileParent = null;
        tile.remove();
      }
    }
    if (k.pane) {
      k.pane.hide();
    }
  }

  api.onEnd.push(function() {
    document.removeEventListener('keydown', onKeyDown, true);
    cleanUpDom();
    k.tile = k.me = tile = tileCard = null;
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

function withTitles(o) {
  var el = document.querySelector('meta[property="og:title"]');
  o.ogTitle = el && el.content.trim();
  o.title = document.title.trim();
  if (o.title && !el) {
    el = document.body.firstElementChild;
    if (el && el.tagName === 'IMG' && el.src === document.URL) {
      o.title = '';  // discard browser-generated title for image file
    }
  }
  return o;
}
