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

  function dispatch() {
    var args = arguments;
    this.forEach(function(f) {f.apply(null, args)});
  }

  function createPage(id, url, skipOnLoading) {
    var page = pages[id] = {id: id, url: url};
    if (!skipOnLoading && httpRe.test(url)) {
      dispatch.call(api.tabs.on.loading, page);
    }
    return page;
  }

  function createPageAndInjectContentScripts(tab, skipOnLoading) {
    var page = createPage(tab.id, tab.url, skipOnLoading);
    if (httpRe.test(tab.url)) {
      if (tab.status === 'complete') {
        injectContentScripts(page);
      } else if (tab.status === 'loading') {
        chrome.tabs.executeScript(tab.id, {code: 'document.readyState', runAt: 'document_start'}, injectContentScriptsIfDomReady.bind(null, page));
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
    dispatch.call(api.icon.on.click, pages[tab.id]);
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
    normalTab[tab.id] = !!selectedTabIds[tab.windowId];
  }));

  chrome.tabs.onActivated.addListener(errors.wrap(function (info) {
    log("#666", "[tabs.onActivated] tab info:", info);
    var prevPageId = selectedTabIds[info.windowId];
    if (prevPageId) { // ignore popups, etc.
      selectedTabIds[info.windowId] = info.tabId;
      var prevPage = pages[prevPageId];
      if (prevPage && httpRe.test(prevPage.url)) {
        dispatch.call(api.tabs.on.blur, prevPage);
      }
      var page = pages[info.tabId];
      if (page && httpRe.test(page.url)) {
        dispatch.call(api.tabs.on.focus, page);
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
        dispatch.call(api.on.search, query, ~details.url.indexOf('sourceid=chrome') ? 'o' : 'n');
      }
    }
  }));

  chrome.webNavigation.onCommitted.addListener(errors.wrap(function (e) {
    if (e.frameId || normalTab[e.tabId] === false) return;
    log('#666', '[onCommitted]', e.tabId, normalTab[e.tabId], e);
    onRemoved(e.tabId, {temp: true});
    createPage(e.tabId, e.url);
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
        dispatch.call(api.tabs.on.unload, page, true);
        page.url = e.url;
        dispatch.call(api.tabs.on.loading, page);
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
      log('#666', '[tabs.onUpdated] %i change: %o', tabId, change);
    }
  }));

  chrome.tabs.onReplaced.addListener(errors.wrap(function (newTabId, oldTabId) {
    log('#666', '[tabs.onReplaced]', oldTabId, '->', newTabId);
    var normal = normalTab[newTabId] = normalTab[oldTabId];
    onRemoved(oldTabId);
    if (normal) {
      var page = pages[newTabId];
      if (page.icon) {
        setPageAction(newTabId, page.icon);
      }
      if (httpRe.test(page.url)) {
        injectContentScripts(page);
      }
    }
  }));

  chrome.tabs.onRemoved.addListener(errors.wrap(onRemoved));
  function onRemoved(tabId, info) {
    if (!info || !info.temp) {
      delete normalTab[tabId];
    }
    var page = pages[tabId];
    if (page) {
      delete pages[tabId];
      if (httpRe.test(page.url)) {
        dispatch.call(api.tabs.on.unload, page);
      }
    }
    var port = ports[tabId];
    if (port) {
      log('#0a0', '[onRemoved] %i disconnecting', tabId);
      port.disconnect();  // Chrome is sometimes slow to disconnect port
      ports[tabId] = null;
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
        dispatch.call(api.tabs.on.blur, page);
      }
    }
    focusedWinId = winId;
    if (winId !== chrome.windows.WINDOW_ID_NONE) {
      if (selectedTabIds[winId]) {  // "normal" window
        topNormalWinId = winId;
      }
      var page = pages[selectedTabIds[winId]];
      if (page && httpRe.test(page.url)) {
        dispatch.call(api.tabs.on.focus, page);
      }
    }
  }));

  chrome.webRequest.onBeforeRequest.addListener(errors.wrap(function () {
    dispatch.call(api.on.beforeSearch, 'o');
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
        createPageAndInjectContentScripts(tab, true);
      }
      if (tab.active) {
        selectedTabIds[tab.windowId] = tab.id;
      }
    });
  }));

  var ports = {}, portHandlers = {
    'api:handling': function (data, _, page, port) {
      for (var i = 0; i < data.length; i++) {
        port.handling[data[i]] = true;
      }
      var toEmit = page.toEmit;
      if (toEmit) {
        for (var i = 0; i < toEmit.length;) {
          var m = toEmit[i];
          if (port.handling[m[0]]) {
            log('#0c0', '[api:handling:emit] %i %s %o', page.id, m[0], m[1] != null ? m[1] : '');
            port.postMessage(m);
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
    log('#0a0', '[onConnect]', tabId, port.name, tab ? tab.url : '');
    if (port.sender.id === chrome.runtime.id && tabId) {
      if (ports[tabId]) {
        log('#a00', '[onConnect] %i disconnecting prev port', tabId);
        ports[tabId].disconnect();
      }
      ports[tabId] = port;
      port.handling = {};
      port.onMessage.addListener(onPortMessage.bind(null, tabId, port));
      port.onDisconnect.addListener(onPortDisconnect.bind(null, tabId, port));
      if (!pages[tabId]) {
        log('#a00', '[onConnect] %i no page', tabId);
      }
    }
  }));
  var onPortMessage = errors.wrap(function (tabId, port, msg) {
    var page = pages[tabId];
    var kind = msg[0], data = msg[1], callbackId = msg[2];
    var handler = portHandlers[kind];
    if (page && handler) {
      log('#0a0', '[onMessage] %i %s', tabId, kind, data != null ? data : '');
      handler(data, respondToTab.bind(null, port, callbackId), page, port);
    } else {
      log('#a00', '[onMessage] %i %s %s %O %s', tabId, kind, 'ignored, page:', page, 'handler:', !!handler);
    }
  });
  var onPortDisconnect = errors.wrap(function (tabId, port) {
    log('#0a0', '[onDisconnect]', tabId);
    delete port.handling;
    if (ports[tabId] === port) {
      delete ports[tabId];
    }
  });

  function respondToTab(port, callbackId, response) {
    if (port.handling) {
      port.postMessage(['api:respond', callbackId, response]);
    }
  }

  var isPackaged = !!chrome.runtime.getManifest().update_url;
  var doLogging = !isPackaged;
  function injectContentScripts(page) {
    if (page.injecting || page.injected) return;
    if (/^https:\/\/chrome.google.com\/webstore/.test(page.url)) {
      log('[injectContentScripts] forbidden', page.url);
      return;
    }
    page.injecting = true;

    var scripts = meta.contentScripts.filter(function(cs) { return cs[1].test(page.url) });

    var injected;
    chrome.tabs.executeScript(page.id, {
      code: 'this.api&&api.injected',
      runAt: 'document_start'
    }, function (arr) {
      injected = arr && arr[0] || {};
      done(0);
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
        if (api.mode.isDev()) {
          chrome.tabs.executeScript(page.id, {code: 'api.dev=1', runAt: 'document_start'}, api.noop);
        }
        page.injected = true;
        delete page.injecting;
        var port = ports[page.id];
        if (port) {
          port.postMessage(['api:injected', Object.keys(injected)]);
          if (doLogging) {
            port.postMessage(['api:log', true]);
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
        log("#bbb", "[injectWithDeps] %i %s", tabId, path);
        inject(tabId, {file: path, runAt: 'document_end'}, afterInject);
      });
    } else {
      callback();
    }
  }

  var onXhrLoadEnd = errors.wrap(function onXhrLoadEnd(done, fail) {
    if (this.status >= 200 && this.status < 300 && /^application\/json/.test(this.getResponseHeader('Content-Type'))) {
      if (done) done(JSON.parse(this.responseText));
    } else {
      if (fail) fail(this);
    }
  });

  function setPageAction(tabId, path) {
    chrome.pageAction.setIcon({tabId: tabId, path: {38: path}});
    chrome.pageAction.show(tabId);
  }

  var hostRe = /^https?:\/\/[^\/]*/;
  return {
    bookmarks: {
      create: function(parentId, name, url, callback) {
        chrome.bookmarks.create({parentId: parentId, title: name, url: url}, errors.wrap(callback));
      },
      createFolder: function(parentId, name, callback) {
        chrome.bookmarks.create({parentId: parentId, title: name}, errors.wrap(callback));
      },
      get: function(id, callback) {
        chrome.bookmarks.get(id, errors.wrap(function (bm) {
          callback(bm && bm[0]);
        }));
      },
      getAll: function(callback) {
        chrome.bookmarks.getTree(errors.wrap(function (bm) {
          var arr = [];
          !function traverse(b) {
            if (b.children) {
              b.children.forEach(traverse);
            } else if (httpRe.test(b.url)) {
              arr.push({id: b.id, url: b.url, title: b.title});
            }
          }(bm && bm[0]);
          callback(arr);
        }));
      },
      getBarFolder: function(callback) {
        chrome.bookmarks.getChildren("0", function(bm) {
          callback(bm.filter(function(bm) { return bm.title == "Bookmarks Bar" })[0] || bm[0]);
        });
      },
      getChildren: chrome.bookmarks.getChildren.bind(chrome.bookmarks),
      move: function(id, newParentId) {
        chrome.bookmarks.move(id, {parentId: newParentId});
      },
      remove: chrome.bookmarks.remove.bind(chrome.bookmarks),
      search: chrome.bookmarks.search.bind(chrome.bookmarks)
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
    request: function(method, uri, data, done, fail) {
      var xhr = new XMLHttpRequest();
      xhr.addEventListener('loadend', onXhrLoadEnd.bind(xhr, done, fail));
      xhr.open(method, uri, true);
      if (data != null && data !== '') {
        if (typeof data !== 'string') {
          data = JSON.stringify(data);
        }
        xhr.setRequestHeader('Content-Type', 'application/json; charset=utf-8');
      }
      xhr.send(data);
    },
    postRawAsForm: function(uri, data) {
      var xhr = new XMLHttpRequest();
      xhr.open('POST', uri, true);
      xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
      xhr.send(data);
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
      open: function(url, handlers, onConnect, onDisconnect) {
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
      anyAt: function(url) {
        for (var id in pages) {
          var page = pages[id];
          if (page.url == url) {
            return page;
          }
        }
      },
      select: function(tabId) {
        chrome.tabs.update(tabId, {active: true}, errors.wrap(function (tab) {
          if (tab) {
            chrome.windows.update(tab.windowId, {focused: true});
          }
        }));
      },
      open: function(url, callback) {
        chrome.tabs.create({url: url}, errors.wrap(function (tab) {
          callback && callback(tab.id);
        }));
      },
      selectOrOpen: function(url) {
        var tab = api.tabs.anyAt(url);
        if (tab) {
          api.tabs.select(tab.id);
        } else {
          api.tabs.open(url);
        }
      },
      close: function(tabId) {
        chrome.tabs.remove(tabId);
      },
      each: function(callback) {
        for (var id in pages) {
          var page = pages[id];
          if (httpRe.test(page.url)) callback(page);
        }
      },
      eachSelected: function(callback) {
        for (var winId in selectedTabIds) {
          var page = pages[selectedTabIds[winId]];
          if (page && httpRe.test(page.url)) callback(page);
        }
      },
      emit: function(tab, type, data, opts) {
        var page = pages[tab.id];
        if (page && (page === tab || page.url.match(hostRe)[0] == tab.url.match(hostRe)[0])) {
          var port = ports[tab.id];
          if (port && port.handling[type]) {
            log('#0c0', '[api.tabs.emit] %i %s %O', tab.id, type, data);
            port.postMessage([type, data]);
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
      get: function(tabId) {
        return pages[tabId];
      },
      getFocused: function () {
        var id = selectedTabIds[focusedWinId];
        return id ? pages[id] : null;
      },
      isFocused: function(tab) {
        return selectedTabIds[focusedWinId] === tab.id;
      },
      navigate: function(tabId, url) {
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
        window.setTimeout(errors.wrap(f), ms);
      },
      setInterval: function (f, ms) {
        window.setInterval(errors.wrap(f), ms);
      },
      clearTimeout: window.clearTimeout.bind(window),
      clearInterval: window.clearInterval.bind(window)
    },
    version: chrome.app.getDetails().version
  };
}());
