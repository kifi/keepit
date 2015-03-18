// API for main.js

var hexRe = /^#[0-9a-f]{3}$/i;
function log(a0) {
  'use strict';
  var args = arguments;
  if (hexRe.test(a0)) {
    args[0] = "%c" + args[1];
    args[1] = "color:" + a0;
  }
  console.log.apply(console, args);
}

var api = (function createApi() {
  'use strict';
  var httpRe = /^https?:/;

  var errors = [];
  errors.wrap = function (fn) {
    return function wrapped() {
      try {
        return fn.apply(this, arguments)
      } catch (e) {
        errors.push({error: e, params: {arguments: Array.prototype.slice.call(arguments)}})
      }
    };
  }

  // Here's how tabs/pages work in this adapter. This is coming from someone with a strong distaste
  // for comments. It's a good idea to verify these statements to ensure they're still accurate.
  // - A page object is normally created when a page load is committed (all redirects resolved).
  // - A page object has the same lifetime as a content script scope and its port; we update page.url
  //   on fragment or history API changes and discard a page object only when webpage is truly unloaded.
  // - Some kifi.com and google.com pages have content scripts injected automatically via the manifest
  //   because that mechanism gets it done earliest. It even happens in invisible preloading tabs,
  //   which can then open a port even before becoming visible (by replacing a real tab).
  // - We discard a page object (in onRemoved) when 1) a new page load is committed in the same tab,
  //   the page's tab is closed, 3) a page load in a hidden tab is aborted, 4) a page's port disconnects.
  // - chrome.tabs.executeScript and .insertCSS fail on preloading tabs, so content scripts must
  //   only use api:require based on a user interaction, thus guaranteeing the tab is visible.
  // - on-document-ready content script injection never happens in an invisible preloading tab.

  // Garbage collection of old, leaked pages was originally written for invisible preloading tabs
  // that Chrome ended up never showing. No Chrome API event is designed to fire when one of these
  // is discarded. However, in practice, two events seem to be good indicators: 1) onErrorOccurred
  // with message net::ERR_ABORTED and/or 2) onDisconnect from a preloading tab's port. Might be okay
  // to remove this now.
  var gcPagesLastRun = Date.now();
  function gcPages(now) {
    for (var id in pages) {
      if (!(id in normalTab)) {
        var page = pages[id];
        if (now - page._created > 90000) {
          log('#666', '[gcPages]', page);
          delete pages[id];
        }
      }
    }
  }

  function createPage(id, url) {
    var now = Date.now();
    var page = pages[id] = {url: url};
    Object.defineProperty(page, 'id', {value: id, enumerable: true});
    Object.defineProperty(page, '_created', {value: now});
    if (now - gcPagesLastRun > 300000) {
      gcPagesLastRun = now;
      gcPages(now);
    }
    return page;
  }

  function injectContentScriptsWhenDomReady(page, status) {
    if (httpRe.test(page.url)) {
      if (status === 'complete') {
        injectContentScripts(page);
      } else if (status === 'loading') {
        chrome.tabs.executeScript(page.id, {code: 'document.readyState', runAt: 'document_start'}, injectContentScriptsIfDomReady.bind(null, page));
      }
    }
  }

  var injectContentScriptsIfDomReady = errors.wrap(function (page, arr) {
    if (arr && (arr[0] === 'interactive' || arr[0] === 'complete')) {
      injectContentScripts(page);
    }
  });

  chrome.pageAction.onClicked.addListener(errors.wrap(function (tab) {
    log("[pageAction.onClicked]", tab);
    api.icon.on.click.dispatch(pages[tab.id]);
  }));

  chrome.runtime.onStartup.addListener(errors.wrap(function () {
    log("[onStartup]");
    api.loadReason = "startup";
  }));

  chrome.runtime.onInstalled.addListener(errors.wrap(function (details) {
    log("[onInstalled] details:", details);
    if (details.reason === "install" || details.reason === "update") {
      api.loadReason = details.reason;
    }
  }));

  var updateCheckRequested, updateVersion;
  chrome.runtime.onUpdateAvailable.addListener(errors.wrap(function (details) {
    log("#666", "[onUpdateAvailable]", updateVersion = details.version);
    if (updateCheckRequested) {
      chrome.runtime.reload();
    }
  }));

  chrome.tabs.onCreated.addListener(errors.wrap(function (tab) {
    log("#666", "[tabs.onCreated]", tab.id, tab.url);
    normalTab[tab.id] = tab.windowId in selectedTabIds;
  }));

  chrome.tabs.onActivated.addListener(errors.wrap(function (info) {
    log("#666", "[tabs.onActivated] tab info:", info);
    var prevPageId = selectedTabIds[info.windowId];
    if (prevPageId) { // ignore popups, etc.
      selectedTabIds[info.windowId] = info.tabId;
      var prevPage = pages[prevPageId];
      if (prevPage && httpRe.test(prevPage.url)) {
        api.tabs.on.blur.dispatch(prevPage);
      }
      var page = pages[info.tabId];
      if (page && httpRe.test(page.url)) {
        api.tabs.on.focus.dispatch(page);
      }
    }
  }));

  var stripHashRe = /^[^#]*/;
  var googleSearchRe = /^https?:\/\/www\.google\.[a-z]{2,3}(?:\.[a-z]{2})?\/(?:|search|webhp)[\?#](?:.*&)?q=([^&#]*)/;
  var plusRe = /\+/g;

  chrome.webNavigation.onBeforeNavigate.addListener(errors.wrap(function (details) {
    var match = details.url.match(googleSearchRe);
    if (match && details.frameId === 0) {
      var query;
      try {
        query = decodeURIComponent(match[1].replace(plusRe, ' ')).trim();
      } catch (e) {
        log('[onBeforeNavigate] non-UTF-8 search query:', match[1], e);  // e.g. www.google.co.il/search?hl=iw&q=%EE%E9%E4
      }
      if (query) {
        api.on.search.dispatch(query, ~details.url.indexOf('sourceid=chrome') ? 'o' : 'n');
      }
    }
  }));

  chrome.webNavigation.onErrorOccurred.addListener(errors.wrap(function (e) {
    if (e.frameId === 0 && e.error === 'net::ERR_ABORTED' && !(e.tabId in normalTab)) {  // invisible preloading tab discarded
      log('#666', '[onErrorOccurred] %i aborted', e.tabId);
      onRemoved(e.tabId);
    }
  }));

  chrome.webNavigation.onCommitted.addListener(errors.wrap(function (e) {
    if (e.frameId === 0) {
      var tabId = e.tabId;
      var normal = normalTab[tabId];
      log('#666', '[onCommitted]', tabId, normal ? 'normal' : normal == null ? 'invisible' : 'peculiar', e.url);
      if (tabId in pages) {
        onRemoved(tabId, {temp: true});
      }
      var page = createPage(tabId, e.url);
      if (normal && httpRe.test(page.url)) {
        api.tabs.on.loading.dispatch(page);
      }
    }
  }));

  chrome.webNavigation.onDOMContentLoaded.addListener(errors.wrap(function (details) {
    if (!details.frameId && normalTab[details.tabId] && httpRe.test(details.url)) {
      var page = pages[details.tabId];
      if (page) {
        if (page.url === details.url) {
          log('[onDOMContentLoaded]', details.tabId, details.url);
        } else {
          log('#a00', '[onDOMContentLoaded] %i url mismatch:\n%s\n%s', details.tabId, details.url, page.url);
        }
        injectContentScripts(page);
      } else {
        log('#a00', '[onDOMContentLoaded] no page for', details.tabId, details.url);
      }
    }
  }));

  chrome.webNavigation.onHistoryStateUpdated.addListener(errors.wrap(function (e) {
    if (e.frameId || normalTab[e.tabId] === false) return;
    log('#666', '[onHistoryStateUpdated]', e.tabId, e);
    var page = pages[e.tabId];
    if (page && page.url !== e.url) {
      if (httpRe.test(page.url) && page.url.match(stripHashRe)[0] != e.url.match(stripHashRe)[0]) {
        api.tabs.on.unload.dispatch(page, true);
        page.url = e.url;
        page.usedHistoryApi = true;
        api.tabs.on.loading.dispatch(page);
      } else {
        page.url = e.url;
      }
    }
  }));

  chrome.webNavigation.onReferenceFragmentUpdated.addListener(errors.wrap(function (e) {
    if (e.frameId || normalTab[e.tabId] === false) return;
    log('#666', '[onReferenceFragmentUpdated]', e.tabId, e);
    var page = pages[e.tabId];
    if (page) {
      page.url = e.url;
    }
  }));

  chrome.tabs.onUpdated.addListener(errors.wrap(function (tabId, change) {
    if ((change.status || change.url) && normalTab[tabId]) {
      log('#666', '[onUpdated] %i change: %o', tabId, change);
    }
  }));

  chrome.tabs.onReplaced.addListener(errors.wrap(function (addedTabId, removedTabId) {
    log('#666', '[tabs.onReplaced]', removedTabId, '<-', addedTabId);
    var normal = normalTab[addedTabId] = normalTab[removedTabId];
    onRemoved(removedTabId);
    if (normal) {
      var page = pages[addedTabId];
      if (page && page.icon) {
        setPageAction(addedTabId, page.icon);
      }
      if (page && httpRe.test(page.url)) {
        api.tabs.on.loading.dispatch(page);
        injectContentScripts(page); // TODO: verify DOM ready first
      }
    }
  }));

  chrome.tabs.onRemoved.addListener(errors.wrap(onRemoved));
  function onRemoved(tabId, info) {
    log('#666', '[onRemoved]', tabId, info || '');
    var normal = normalTab[tabId];
    if (normal != null && (!info || !info.temp)) {
      delete normalTab[tabId];
    }
    var page = info && info.page || pages[tabId];
    if (page) {
      if (page === pages[tabId]) {
        delete pages[tabId];
      }
      if (normal && httpRe.test(page.url)) {
        api.tabs.on.unload.dispatch(page);
      }
      if (page._port) {
        log('#0a0', '[onRemoved] %i disconnecting', tabId);
        page._port.disconnect();  // Chrome is sometimes slow to disconnect port
      }
    }
  }

  chrome.windows.onCreated.addListener(errors.wrap(function (win) {
    if (win.type === 'normal') {
      selectedTabIds[win.id] = -1;  // indicates that the window is normal (supports tabs)
    }
  }));

  chrome.windows.onRemoved.addListener(errors.wrap(function (winId) {
    delete selectedTabIds[winId];
  }));

  var focusedWinId, topNormalWinId;
  chrome.windows.getLastFocused(null, errors.wrap(function (win) {
    focusedWinId = win && win.focused ? win.id : chrome.windows.WINDOW_ID_NONE;
    topNormalWinId = win && win.type == 'normal' ? win.id : chrome.windows.WINDOW_ID_NONE;
  }));
  chrome.windows.onFocusChanged.addListener(errors.wrap(function (winId) {
    log('[onFocusChanged] win %o -> %o', focusedWinId, winId);
    if (focusedWinId > 0) {
      var page = pages[selectedTabIds[focusedWinId]];
      if (page && httpRe.test(page.url)) {
        api.tabs.on.blur.dispatch(page);
      }
    }
    focusedWinId = winId;
    if (winId !== chrome.windows.WINDOW_ID_NONE) {
      if (selectedTabIds[winId]) {  // "normal" window
        topNormalWinId = winId;
      }
      var page = pages[selectedTabIds[winId]];
      if (page && httpRe.test(page.url)) {
        api.tabs.on.focus.dispatch(page);
      }
    }
  }));

  chrome.webRequest.onBeforeRequest.addListener(errors.wrap(function () {
    api.on.beforeSearch.dispatch('o');
  }), {
    tabId: -1,
    types: ['main_frame', 'other'],
    urls: [
      'https://www.google.com/webhp?sourceid=chrome-instant*',
      'https://www.google.com/complete/search?client=chrome-omni*'
    ]
  });

  var pages = {};  // by tab.id in "normal" windows only
  var normalTab = {};  // by tab.id (true if tab is in a "normal" window)
  var selectedTabIds = {};  // by window.id in "normal" windows only
  chrome.tabs.query({windowType: 'normal'}, errors.wrap(function (tabs) {
    tabs.forEach(function (tab) {
      normalTab[tab.id] = true;
      if (!pages[tab.id]) {
        var page = createPage(tab.id, tab.url);
        injectContentScriptsWhenDomReady(page, tab.status);
      }
      if (tab.active) {
        selectedTabIds[tab.windowId] = tab.id;
      }
    });
  }));

  var portHandlers = {
    'api:handling': function (data, _, page) {
      for (var i = 0; i < data.length; i++) {
        page._handling[data[i]] = true;
      }
      var toEmit = page.toEmit;
      if (toEmit) {
        for (var i = 0; i < toEmit.length;) {
          var m = toEmit[i];
          if (page._handling[m[0]]) {
            log('#0c0', '[api:handling:emit] %i %s %o', page.id, m[0], m[1] != null ? m[1] : '');
            page._port.postMessage(m);
            toEmit.splice(i, 1);
          } else {
            i++;
          }
        }
        if (!toEmit.length) {
          delete page.toEmit;
        }
      }
    },
    'api:iframe': function (o, _, page) {
      var toUrl = chrome.runtime.getURL;
      chrome.tabs.executeScript(page.id, {
        allFrames: true,
        code: [
          'if (window !== top && document.URL === "', o.url, '") {',
          " document.head.innerHTML='", o.styles.map(function(path) {return '<link rel="stylesheet" href="' + toUrl(path) + '">'}).join(''), "';",
          ' ', JSON.stringify(o.scripts.map(function (path) {return toUrl(path)})), '.forEach(function(url) {',
          '  var s = document.createElement("SCRIPT");',
          '  s.dataset.loading = true;',
          '  s.addEventListener("load", function () { this.dataset.loading = false; });',
          '  s.src = url;',
          '  document.head.appendChild(s);',
          ' });',
          '}'].join(''),
        runAt: 'document_end'
      });
    },
    'api:require': function (data, respond, page) {
      injectWithDeps(page.id, data.paths, data.injected, respond);
    }};
  chrome.runtime.onConnect.addListener(errors.wrap(function (port) {
    var tab = port.sender.tab;
    var tabId = tab && tab.id;
    var normal = tabId && normalTab[tabId];
    if (port.sender.id === chrome.runtime.id && tabId && normal !== false) {
      log('#0a0', '[onConnect]', tabId, port.name, tab.url);
      var page = pages[tabId];
      if (page && page._port) {
        log('#a00', '[onConnect] %i disconnecting prev port', tabId);
        page._port.disconnect(); // onDisconnect will call onRemoved
        page = null;
      }
      if (!page) { // not sure whether this ever happens
        log('#a00', '[onConnect] %i creating page', tabId);
        page = createPage(tab);
        if (normal) {
          injectContentScriptsWhenDomReady(page, tab.status);
        }
      }
      Object.defineProperty(page, '_port', {value: port, configurable: true});
      Object.defineProperty(page, '_handling', {value: {}, configurable: true});
      port.onMessage.addListener(onPortMessage.bind(null, page));
      port.onDisconnect.addListener(onPortDisconnect.bind(null, page));
    } else {
      log('#a00', '[onConnect] disconnecting stranger', port.sender.id, port.name);
      port.disconnect();
    }
  }));
  var onPortMessage = errors.wrap(function (page, msg) {
    var page = pages[page.id];
    var kind = msg[0], data = msg[1], callbackId = msg[2];
    var handler = portHandlers[kind];
    if (handler) {
      log('#0a0', '[onMessage] %i %s', page.id, kind, data != null ? (data.join ? data.join(' ') : data) : '');
      handler(data, respondToTab.bind(null, page, callbackId), page);
    } else {
      log('#a00', '[onMessage] %i %s %s %O %s', page.id, kind, 'ignored, page:', page, 'handler:', !!handler);
    }
  });
  var onPortDisconnect = errors.wrap(function (page) {
    log('#0a0', '[onDisconnect]', page.id);
    Object.defineProperty(page, '_port', {value: null, configurable: false});
    Object.defineProperty(page, '_handling', {value: null, configurable: false});
    if (!(page.id in normalTab)) {  // browser is discarding an invisible preloading tab
      onRemoved(page.id, {page: page});
    }
  });

  function respondToTab(page, callbackId, response) {
    if (page._port) {
      page._port.postMessage(['api:respond', callbackId, response]);
    }
  }

  var isPackaged = !!chrome.runtime.getManifest().update_url;
  var doLogging = !isPackaged;
  function injectContentScripts(page) {
    if (page.injecting || page.injected) return;
    page.injecting = true;

    var scripts = meta.contentScripts.filter(function(cs) { return cs[1].test(page.url) });

    var injected;
    chrome.tabs.executeScript(page.id, {
      code: 'this.api&&api.injected',
      runAt: 'document_start'
    }, function (arr) {
      var err = chrome.runtime.lastError;
      if (err) {
        log('#a80', '[inject] %i fail', page.id, err.message);
        if (err.message === 'The tab was closed.' ||
            err.message === 'The extensions gallery cannot be scripted.' ||
            err.message === 'Cannot access a chrome:// URL') {
          injected = {};
          done();
        } else {  // tab closed?
          errors.push({error: Error('chrome.tabs.executeScript failed'), params: {message: err.message, page: page}});
          delete page.injecting;
        }
      } else {
        injected = arr[0] || {};
        done(0);
      }
    });

    function done(n) {
      if (page !== pages[page.id]) {
        return;
      } else if (n < scripts.length) {
        injectWithDeps(page.id, scripts[n][0], injected, function (paths) {
          for (var i in paths) {
            injected[paths[i]] = true;
          }
          done(n + 1);
        });
      } else {
        if (n && api.mode.isDev()) {
          chrome.tabs.executeScript(page.id, {code: 'api.dev=1', runAt: 'document_start'}, api.noop);
        }
        page.injected = true;
        delete page.injecting;
        if (page._port) {
          page._port.postMessage(['api:injected', Object.keys(injected)]);
          if (doLogging) {
            page._port.postMessage(['api:log', true]);
          }
        }
      }
    }
  }

  function injectWithDeps(tabId, paths, injected, callback) {
    var o = deps(paths, injected), n = 0;
    injectAll(tabId, chrome.tabs.insertCSS.bind(chrome.tabs), o.styles, done);
    injectAll(tabId, chrome.tabs.executeScript.bind(chrome.tabs), o.scripts, done);
    function done() {
      if (++n == 2) {
        callback(o.styles.concat(o.scripts));
      }
    }
  }

  function injectAll(tabId, inject, paths, callback) {
    var n = 0, N = paths.length;
    if (N) {
      var afterInject = errors.wrap(function () {
        if (++n === N) {
          callback();
        }
      });
      paths.forEach(function (path) {
        log('#bbb', '[inject] %i %s', tabId, path);
        inject(tabId, {file: path, runAt: 'document_end'}, afterInject);
      });
    } else {
      callback();
    }
  }

  function setPageAction(tabId, path) {
    chrome.pageAction.setIcon({tabId: tabId, path: {38: path}});
    chrome.pageAction.show(tabId);
  }

  var hostRe = /^https?:\/\/[^\/]*/;
  return {
    bookmarks: {
      getAll: function (callback) {
        chrome.bookmarks.getTree(errors.wrap(function (bm) {
          var arr = [], path;
          !function traverse(node) {
            if (node.children) {
              var name = node.id > 2 && node.title.trim(); // '0','1','2' are '','Bookmarks Bar','Other Bookmarks'
              if (name) {
                path = path ? path.concat([name]) : [name];
              }
              node.children.forEach(traverse);
              if (name) {
                path = path.length > 1 ? path.slice(0, -1) : undefined;
              }
            } else if (httpRe.test(node.url)) {
              arr.push({url: node.url, title: node.title, addedAt: node.dateAdded, path: path});
            }
          }(bm && bm[0]);
          callback(arr);
        }));
      }
    },
    errors: {
      init: function (handler) {
        while (errors.length) {
          handler.push(errors.shift());
        }
        handler.wrap = errors.wrap;
        errors = handler;
      },
      push: function (o) {
        errors.push(o);
      },
      wrap: errors.wrap
    },
    icon: {
      on: {click: new Listeners},
      set: function(tab, path) {
        if (tab === pages[tab.id]) {
          tab.icon = path;
          if (normalTab[tab.id]) {
            setPageAction(tab.id, path);
          }
        }
      }},
    inspect: {
      pages: pages,
      normalTab: normalTab,
      selectedTabIds: selectedTabIds
    },
    isPackaged: function() {
      return isPackaged;
    },
    loadReason: 'enable',  // by elimination
    mode: {
      isDev: function () {
        return localStorage[':mode'] === 'dev';
      },
      toggle: function () {
        if (localStorage[':mode']) {
          delete localStorage[':mode'];
        } else {
          localStorage[':mode'] = 'dev';
        }
        chrome.runtime.reload();
      }
    },
    on: {
      beforeSearch: new Listeners,
      search: new Listeners
    },
    noop: function() {},
    play: function(path) {
      var el = document.createElement("audio");
      el.src = path;
      el.play();
    },
    port: {
      on: function (handlers) {
        for (var k in handlers) {
          if (portHandlers[k]) throw Error(k + ' handler already defined');
          portHandlers[k] = handlers[k];
        }
      }
    },
    util: {
      btoa: window.btoa.bind(window)
    },
    browser: {
      name: 'Chrome',
      userAgent: navigator.userAgent
    },
    requestUpdateCheck: function() {
      if (updateVersion) {
        chrome.runtime.reload();
      } else {
        updateCheckRequested = true;
        chrome.runtime.requestUpdateCheck(errors.wrap(function (status) {
          log("[requestUpdateCheck]", status);
        }));
      }
    },
    screenshot: function (callback) {
      chrome.tabs.captureVisibleTab(null, function (dataUri) {
        var img = document.createElement('img');
        img.src = dataUri;
        callback(img, document.createElement('canvas'));
      });
    },
    socket: {
      open: function (url, handlers, onConnect, onDisconnect) {
        log('[api.socket.open]', url);
        var sc, rws = new ReconnectingWebSocket(url, {
          onConnect: errors.wrap(function () {
            sc.onConnect();
          }),
          onDisconnect: errors.wrap(function (why, sec) {
            sc.onDisconnect(why, sec);
          }),
          onMessage: errors.wrap(function (e) {
            sc.onMessage(e.data);
          })
        });
        return (sc = new SocketCommander(rws, handlers, onConnect, onDisconnect, log));
      }
    },
    storage: localStorage,
    tabs: {
      anyAt: function (url) {
        for (var id in pages) {
          var page = pages[id];
          if (page.url == url) {
            return page;
          }
        }
      },
      select: function (tabId) {
        chrome.tabs.update(tabId, {active: true}, errors.wrap(function (tab) {
          if (tab) {
            chrome.windows.update(tab.windowId, {focused: true});
          }
        }));
      },
      open: function (url, callback) {
        chrome.tabs.create({url: url}, errors.wrap(function (tab) {
          callback && callback(tab.id);
        }));
      },
      selectOrOpen: function (url) {
        var tab = api.tabs.anyAt(url);
        if (tab) {
          api.tabs.select(tab.id);
        } else {
          api.tabs.open(url);
        }
      },
      close: function (tabId) {
        chrome.tabs.remove(tabId);
      },
      each: function (callback) {
        for (var id in pages) {
          var page = pages[id];
          if (httpRe.test(page.url)) callback(page);
        }
      },
      eachSelected: function (callback) {
        for (var winId in selectedTabIds) {
          var page = pages[selectedTabIds[winId]];
          if (page && httpRe.test(page.url)) callback(page);
        }
      },
      emit: function (tab, type, data, opts) {
        var page = pages[tab.id];
        if (page && (page === tab || page.url.match(hostRe)[0] === tab.url.match(hostRe)[0])) {
          if ((page._handling || {})[type]) {
            log('#0c0', '[api.tabs.emit] %i %s %O', tab.id, type, data);
            page._port.postMessage([type, data]);
          } else if (opts && opts.queue) {
            var toEmit = page.toEmit;
            if (toEmit) {
              if (opts.queue === 1) {
                for (var i = 0; i < toEmit.length; i++) {
                  var m = toEmit[i];
                  if (m[0] === type) {
                    m[1] = data;
                    return;
                  }
                }
              }
              toEmit.push([type, data]);
            } else {
              page.toEmit = [[type, data]];
            }
          }
        } else {
          log("#a00", "[api.tabs.emit] suppressed %i %s navigated: %s -> %s", tab.id, type, tab.url, page && page.url);
        }
      },
      get: function (tabId) {
        return pages[tabId];
      },
      getFocused: function () {
        var id = selectedTabIds[focusedWinId];
        return id ? pages[id] : null;
      },
      isFocused: function (tab) {
        return selectedTabIds[focusedWinId] === tab.id;
      },
      navigate: function (tabId, url) {
        chrome.tabs.update(tabId, {url: url, active: true}, errors.wrap(function (tab) {
          if (tab) chrome.windows.update(tab.windowId, {focused: true});
        }));
      },
      on: {
        focus: new Listeners,
        blur: new Listeners,
        loading: new Listeners,
        unload: new Listeners
      },
      reload: function (tabId) {
        chrome.tabs.reload(tabId, {bypassCache: false});
      }
    },
    toggleLogging: function (bool) {
      if (doLogging !== bool) {
        doLogging = bool;
        api.tabs.each(function (page) {
          api.tabs.emit(page, 'api:log', doLogging);
        });
      }
    },
    timers: {
      setTimeout: function (f, ms) {
        return window.setTimeout(errors.wrap(f), ms);
      },
      setInterval: function (f, ms) {
        return window.setInterval(errors.wrap(f), ms);
      },
      clearTimeout: window.clearTimeout.bind(window),
      clearInterval: window.clearInterval.bind(window)
    },
    version: chrome.app.getDetails().version,
    xhr: XMLHttpRequest
  };
}());
