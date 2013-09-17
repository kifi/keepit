// API for main.js

api = function() {
  function dispatch() {
    var args = arguments;
    this.forEach(function(f) {f.apply(null, args)});
  }

  function createPage(tab, skipOnLoading) {
    var page = pages[tab.id] = {id: tab.id, url: tab.url};
    if (tab.active) {
      selectedTabIds[tab.windowId] = tab.id;
    }
    if (!skipOnLoading && /^https?:/.test(tab.url)) {
      dispatch.call(api.tabs.on.loading, page);
    }
    return page;
  }

  function createPageAndInjectContentScripts(tab, skipOnLoading) {
    var page = createPage(tab, skipOnLoading);
    if (/^https?:/.test(tab.url)) {
      if (tab.status === "complete") {
        injectContentScripts(page);
      } else if (tab.status === "loading") {
        // we might want to dispatch on.loading here.
        chrome.tabs.executeScript(tab.id, {code: "document.readyState", runAt: "document_start"}, function(arr) {
          if (arr && (arr[0] === "interactive" || arr[0] === "complete")) {
            injectContentScripts(page);
          }
        });
      }
    }
  }

  chrome.pageAction.onClicked.addListener(function(tab) {
    api.log("[pageAction.onClicked]", tab);
    dispatch.call(api.icon.on.click, pages[tab.id]);
  });

  chrome.runtime.onStartup.addListener(function() {
    api.log("[onStartup]");
    api.loadReason = "startup";
    dispatch.call(api.on.startup);
  });

  chrome.runtime.onInstalled.addListener(function(details) {
    api.log("[onInstalled] details:", details);
    if (details.reason === "install" || details.reason === "update") {
      api.loadReason = details.reason;
      dispatch.call(api.on[details.reason]);
    }
  });

  var updateCheckRequested, updateVersion;
  chrome.runtime.onUpdateAvailable.addListener(function(details) {
    api.log("#666", "[onUpdateAvailable]", updateVersion = details.version);
    if (updateCheckRequested) {
      chrome.runtime.reload();
    }
  });

  chrome.tabs.onCreated.addListener(function(tab) {
    api.log("#666", "[tabs.onCreated]", tab.id, tab.url);
  });

  chrome.tabs.onActivated.addListener(function(info) {
    api.log("#666", "[tabs.onActivated] tab info:", info);
    var prevPageId = selectedTabIds[info.windowId];
    if (prevPageId) { // ignore popups, etc.
      selectedTabIds[info.windowId] = info.tabId;
      var prevPage = pages[prevPageId];
      if (prevPage && /^https?:/.test(prevPage.url)) {
        dispatch.call(api.tabs.on.blur, prevPage);
      }
      var page = pages[info.tabId];
      if (page && /^https?:/.test(page.url)) {
        dispatch.call(api.tabs.on.focus, page);
      }
    }
  });

  const googleSearchPattern = /^https?:\/\/www\.google\.[a-z]{2,3}(?:\.[a-z]{2})?\/(?:|search|webhp)\?(?:.*&)?q=([^&#]*)/;
  const plusPattern = /\+/g;
  chrome.webNavigation.onBeforeNavigate.addListener(function(details) {
    var match = details.url.match(googleSearchPattern);
    if (match && details.frameId === 0) {
      dispatch.call(api.on.search, decodeURIComponent(match[1].replace(plusPattern, ' ')));
    }
  });

  chrome.webNavigation.onDOMContentLoaded.addListener(function(details) {
    if (!details.frameId && /^https?:/.test(details.url)) {
      var page = pages[details.tabId];
      if (page) {
        if (page.url === details.url) {
          api.log("[onDOMContentLoaded]", details.tabId, details.url);
        } else {
          api.log("#a00", "[onDOMContentLoaded] %i url mismatch:\n%s\n%s", details.tabId, details.url, page.url);
        }
        injectContentScripts(page);
      } else if (details.tabId >= 0) {
        chrome.tabs.get(details.tabId, function(tab) {
          if (tab && selectedTabIds[tab.windowId]) {  // normal win
            api.log("#a00", "[onDOMContentLoaded] no page for", details.tabId, details.url);
          }
        });
      }
    }
  });

  chrome.tabs.onUpdated.addListener(function(tabId, change, tab) {
    api.log("#666", "[tabs.onUpdated] %i change: %o", tabId, change);
    if (selectedTabIds[tab.windowId]) {  // window is "normal"
      if (change.status === "loading" && (change.url || !ports[tabId])) {  // app.asana.com/0/7052550309820/7364261745177
        onRemoved(tabId);
        createPage(tab);
      }
    }
  });

  chrome.tabs.onReplaced.addListener(function(newTabId, oldTabId) {
    api.log("#666", "[tabs.onReplaced]", oldTabId, "->", newTabId);
    onRemoved(oldTabId);
    chrome.tabs.get(newTabId, function(tab) {
      if (tab) {
        if (selectedTabIds[tab.windowId] === oldTabId) {
          selectedTabIds[tab.windowId] = newTabId;
        }
        createPageAndInjectContentScripts(tab);
      } else {
        api.log("#800", "[onReplaced] %i no tab", newTabId);
      }
    });
  });

  chrome.tabs.onRemoved.addListener(onRemoved);
  function onRemoved(tabId) {
    var page = pages[tabId];
    if (page) {
      delete pages[tabId];
      if (/^https?:/.test(page.url)) {
        dispatch.call(api.tabs.on.unload, page);
      }
    }
  }

  chrome.windows.onCreated.addListener(function(win) {
    if (win.type === "normal") {
      selectedTabIds[win.id] = -1;  // indicates that the window is normal (supports tabs)
    }
  });

  chrome.windows.onRemoved.addListener(function(winId) {
    delete selectedTabIds[winId];
  });

  var focusedWinId, topNormalWinId;
  chrome.windows.getLastFocused(null, function(win) {
    focusedWinId = win && win.focused ? win.id : chrome.windows.WINDOW_ID_NONE;
    topNormalWinId = win && win.type == "normal" ? win.id : chrome.windows.WINDOW_ID_NONE;
  });
  chrome.windows.onFocusChanged.addListener(function(winId) {
    api.log("[onFocusChanged] win %o -> %o", focusedWinId, winId);
    if (focusedWinId > 0) {
      var page = pages[selectedTabIds[focusedWinId]];
      if (page && /^https?:/.test(page.url)) {
        dispatch.call(api.tabs.on.blur, page);
      }
    }
    focusedWinId = winId;
    if (winId !== chrome.windows.WINDOW_ID_NONE) {
      if (selectedTabIds[winId]) {  // "normal" window
        topNormalWinId = winId;
      }
      var page = pages[selectedTabIds[winId]];
      if (page && /^https?:/.test(page.url)) {
        dispatch.call(api.tabs.on.focus, page);
      }
    }
  });

  const pages = {};  // by tab.id
  const selectedTabIds = {};  // in "normal" windows only, by window.id (allows us to avoid some async chrome API calls)
  chrome.tabs.query({windowType: "normal"}, function(tabs) {
    tabs.forEach(function(tab) {
      if (!pages[tab.id]) {
        createPageAndInjectContentScripts(tab, true);
      }
    });
  });

  const ports = {}, portHandlers = {
    "api:handling": function(data, _, page, port) {
      for (var i = 0; i < data.length; i++) {
        port.handling[data[i]] = true;
      }
      if (page.toEmit) {
        for (var i = 0; i < page.toEmit.length;) {
          var m = page.toEmit[i];
          if (port.handling[m[0]]) {
            api.log("#0c0", "[api:handling:emit] %i %s %o", page.id, m[0], m[1] != null ? m[1] : "");
            port.postMessage(m);
            page.toEmit.splice(i, 1);
          } else {
            i++;
          }
        }
        if (!page.toEmit.length) {
          delete page.toEmit;
        }
      }
    },
    "api:reload": function() {
      if (!api.isPackaged()) {
        chrome.runtime.reload();
      }
    },
    "api:require": function(data, respond, page) {
      injectWithDeps(page.id, data.paths, data.injected, respond);
    }};
  chrome.runtime.onConnect.addListener(function(port) {
    var tab = port.sender.tab;
    api.log("#0a0", "[onConnect]", port.name, tab && tab.id, tab && tab.url);
    if (port.sender.id === chrome.runtime.id && tab) {
      if (ports[tab.id]) {
        api.log("#a00", "[onConnect] %i disconnecting prev port", tab.id);
        ports[tab.id].disconnect();
      }
      ports[tab.id] = port;
      port.handling = {};
      port.onMessage.addListener(function(msg) {
        var page = pages[tab.id];
        var kind = msg[0], data = msg[1], callbackId = msg[2];
        var handler = portHandlers[kind];
        if (page && handler) {
          if (page.url !== tab.url) {
            api.log("#0a0", "[onMessage] %i %s new url:\n%s →\n%s", tab.id, kind, page.url, tab.url);
            page.url = tab.url;
          }
          api.log("#0a0", "[onMessage] %i %s", tab.id, kind, data != null ? data : "");
          handler(data, respondToTab.bind(port, callbackId), page, port);
        } else {
          api.log("#a00", "[onMessage] %i %s %s %O %s", tab.id, kind, "ignored, page:", page, "handler:", !!handler);
        }
      });
      port.onDisconnect.addListener(function() {
        api.log("#0a0", "[onDisconnect]", tab.id);
        delete ports[tab.id].handling;
        delete ports[tab.id];
      });
    }
  });

  function respondToTab(callbackId, response) {
    this.handling && this.postMessage(["api:respond", callbackId, response]);
  }

  function injectContentScripts(page) {
    if (page.injecting || page.injected) return;
    if (/^https:\/\/chrome.google.com\/webstore/.test(page.url)) {
      api.log("[injectContentScripts] forbidden", page.url);
      return;
    }
    page.injecting = true;

    var scripts = meta.contentScripts.filter(function(cs) { return !cs[2] && cs[1].test(page.url) });

    var js = api.prefs.get('suppressLog') ? 'var suppressLog=true;' : '', injected;
    chrome.tabs.executeScript(page.id, {code: js + "this.api&&api.injected", runAt: "document_start"}, function(arr) {
      injected = arr[0] || {};
      done(0);
    });

    function done(n) {
      if (n < scripts.length) {
        injectWithDeps(page.id, scripts[n][0], injected, function(paths) {
          for (var i in paths) {
            injected[paths[i]] = true;
          }
          done(n + 1);
        });
      } else {
        if (localStorage[":env"] == "development") {
          chrome.tabs.executeScript(page.id, {code: "api.dev=1", runAt: "document_start"}, api.noop);
        }
        api.tabs.emit(page, "api:injected", Object.keys(injected));
        page.injected = true;
        delete page.injecting;
      }
    }
  }

  function injectWithDeps(tabId, paths, injected, callback) {
    var o = deps(paths, injected), n = 0;
    injectAll(chrome.tabs.insertCSS.bind(chrome.tabs), o.styles, done);
    injectAll(chrome.tabs.executeScript.bind(chrome.tabs), o.scripts, done);
    function done() {
      if (++n == 2) {
        callback(o.styles.concat(o.scripts));
      }
    }
    function injectAll(inject, paths, callback) {
      var n = 0, N = paths.length;
      if (N) {
        paths.forEach(function(path) {
          api.log("#bbb", "[injectWithDeps] %i %s", tabId, path);
          inject(tabId, {file: path, runAt: "document_end"}, function() {
            if (++n === N) {
              callback();
            }
          });
        });
      } else {
        callback();
      }
    }
  }

  const hostRe = /^https?:\/\/[^\/]*/, hexRe = /^#[0-9a-f]{3}$/i;
  return {
    bookmarks: {
      create: function(parentId, name, url, callback) {
        chrome.bookmarks.create({parentId: parentId, title: name, url: url}, callback);
      },
      createFolder: function(parentId, name, callback) {
        chrome.bookmarks.create({parentId: parentId, title: name}, callback);
      },
      get: function(id, callback) {
        chrome.bookmarks.get(id, function(bm) {
          callback(bm && bm[0]);
        });
      },
      getAll: function(callback) {
        chrome.bookmarks.getTree(function(bm) {
          var arr = [];
          !function traverse(b) {
            if (b.children) {
              b.children.forEach(traverse);
            } else if (/^https?:/.test(b.url)) {
              arr.push({id: b.id, url: b.url, title: b.title});
            }
          }(bm && bm[0]);
          callback(arr);
        });
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
      search: chrome.bookmarks.search.bind(chrome.bookmarks)},
    icon: {
      on: {click: new Listeners},
      set: function(tab, path) {
        if (tab === pages[tab.id]) {
          tab.icon = path;
          chrome.pageAction.setIcon({tabId: tab.id, path: {"38": path}});
          chrome.pageAction.show(tab.id);
        }
      }},
    inspect: {
      pages: pages,
      selectedTabIds: selectedTabIds
    },
    isPackaged: function() {
      return !!chrome.runtime.getManifest().update_url;
    },
    loadReason: "enable",  // assuming "enable" by elimination
    log: function() {
      var d = new Date, ds = d.toString(), t = "[" + ds.substr(0, 2) + ds.substr(15,9) + "." + String(+d).substr(10) + "] ";
      if (hexRe.test(arguments[0])) {
        var c = arguments[0];
        arguments[0] = "%c" + t + arguments[1];
        arguments[1] = "color:" + c;
      } else {
        arguments[0] = t + arguments[0];
      }
      console.log.apply(console, arguments);
    },
    on: {
      search: new Listeners,
      install: new Listeners,
      update: new Listeners,
      startup: new Listeners},
    noop: function() {},
    play: function(path) {
      var el = document.createElement("audio");
      el.src = path;
      el.play();
    },
    popup: {
      open: function(options, handlers) {
        var popupWinId, popupTabId;
        options.type = "popup";
        delete options.name;
        chrome.windows.create(options, function(win) {
          popupWinId = win.id;
          popupTabId = win.tabs[0].id;
        });
        if (handlers && handlers.navigate) {
          chrome.tabs.onUpdated.addListener(onUpdated);
          chrome.windows.onRemoved.addListener(onClosed);
        }
        function onUpdated(tabId, changed, tab) {
          if (tabId == popupTabId && changed.status === "loading") {
            handlers.navigate.call({close: function() {
              chrome.windows.remove(popupWinId);
            }}, changed.url);
          }
        }
        function onClosed(winId) {
          chrome.tabs.onUpdated.removeListener(onUpdated);
          chrome.windows.onRemoved.removeListener(onClosed);
        }
      }},
    port: {
      on: function(handlers) {
        for (var k in handlers) {
          if (portHandlers[k]) throw Error(k + " handler already defined");
          portHandlers[k] = handlers[k];
        }
      }
    },
    prefs: {
      get: function get(key) {
        if (arguments.length > 1) {
          for (var o = {}, i = 0; i < arguments.length; i++) {
            key = arguments[i];
            o[key] = get(key);
          }
          return o;
        }
        var v = localStorage[":" + key];
        if (v != null) try {
          return JSON.parse(v);
        } catch (e) {}
        return {showSlider: true, maxResults: 5, showScores: false}[key] || v;  // TODO: factor our default settings out of this API
      },
      set: function set(key, value) {
        if (typeof key === "object") {
          Object.keys(key).forEach(function(k) {
            set(k, key[k]);
          });
        } else if (value == null) {
          delete localStorage[":" + key];
        } else {
          localStorage[":" + key] = typeof value === "string" ? value : JSON.stringify(value);
        }
      }
    },
    request: function(method, uri, data, done, fail) {
      var xhr = new XMLHttpRequest();
      xhr.onreadystatechange = function() {
        if (this.readyState == 4) {
          if (this.status >= 200 && this.status < 300) {
            done && done(/^application\/json/.test(this.getResponseHeader("Content-Type")) ? JSON.parse(this.responseText) : this);
          } else if (fail) {
            fail(this);
          }
          done = fail = null;
        }
      }
      xhr.open(method, uri, true);
      if (data) {
        data = JSON.stringify(data);
        xhr.setRequestHeader("Content-Type", "application/json; charset=utf-8");
      }
      xhr.send(data);
    },
    requestUpdateCheck: function() {
      if (updateVersion) {
        chrome.runtime.reload();
      } else {
        updateCheckRequested = true;
        chrome.runtime.requestUpdateCheck(function(status) {
          api.log("[requestUpdateCheck]", status);
        });
      }
    },
    socket: {
      open: function(url, handlers, onConnect, onDisconnect) {
        var callbacks = {}, nextCallbackId = 1;  // TODO: garbage collect old uncalled callbacks
        var rws = new ReconnectingWebSocket({
          url: url,
          onConnect: function() {
            socket.seq++;
            onConnect();
          },
          onDisconnect: onDisconnect,
          onMessage: function(e) {
            var msg = JSON.parse(e.data);
            if (Array.isArray(msg)) {
              var id = msg.shift();
              if (id > 0) {
                var cb = callbacks[id];
                if (cb) {
                  api.log("#0ac", "[socket.receive] response", id, "(" + (new Date - cb[1]) +  "ms)");
                  delete callbacks[id];
                  cb[0].apply(null, msg);
                } else {
                  api.log("#0ac", "[socket.receive] ignoring", [id].concat(msg));
                }
              } else {
                var handler = handlers[id];
                if (handler) {
                  api.log("#0ac", "[socket.receive]", id);
                  handler.apply(null, msg);
                } else {
                  api.log("#0ac", "[socket.receive] ignoring", [id].concat(msg));
                }
              }
            } else {
              api.log("#0ac", "[socket.receive] ignoring", msg);
            }
          }});
        var socket = {
          seq: 0,
          send: function(arr, callback) {
            if (callback) {
              var id = nextCallbackId++;
              callbacks[id] = [callback, Date.now()];
              arr.splice(1, 0, id);
            }
            api.log("#0ac", "[socket.send]", arr);
            rws.send(JSON.stringify(arr));
          },
          close: function() {
            rws.close();
            this.send = this.close = api.noop;
          }
        };
        return socket;
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
        chrome.tabs.update(tabId, {active: true});
      },
      open: function(url, callback) {
        chrome.tabs.create({url: url}, function(tab) {
          callback && callback(tab.id);
        });
      },
      each: function(callback) {
        for (var id in pages) {
          var page = pages[id];
          if (/^https?:/.test(page.url)) callback(page);
        }
      },
      eachSelected: function(callback) {
        for (var winId in selectedTabIds) {
          var page = pages[selectedTabIds[winId]];
          if (page && /^https?:/.test(page.url)) callback(page);
        }
      },
      emit: function(tab, type, data, opts) {
        var page = pages[tab.id];
        if (page && (page === tab || page.url.match(hostRe)[0] == tab.url.match(hostRe)[0])) {
          var port = ports[tab.id];
          if (port && port.handling[type]) {
            api.log("#0c0", "[api.tabs.emit] %i %s %O", tab.id, type, data);
            port.postMessage([type, data]);
          } else if (opts && opts.queue) {
            if (page.toEmit) {
              if (opts.queue === 1) {
                for (var i = 0; i < page.toEmit.length; i++) {
                  if (page.toEmit[i][0] === type) {
                    page.toEmit[i][1] = data;
                    return;
                  }
                }
              }
              page.toEmit.push([type, data]);
            } else {
              page.toEmit = [[type, data]];
            }
          }
        } else {
          api.log("#a00", "[api.tabs.emit] suppressed %i %s navigated: %s -> %s", tab.id, type, tab.url, page && page.url);
        }
      },
      get: function(tabId) {
        return pages[tabId];
      },
      isFocused: function(tab) {
        return selectedTabIds[focusedWinId] === tab.id;
      },
      navigate: function(tabId, url) {
        chrome.tabs.update(tabId, {url: url, active: true}, function(tab) {
          if (tab) chrome.windows.update(tab.windowId, {focused: true});
        });
      },
      on: {
        focus: new Listeners,
        blur: new Listeners,
        loading: new Listeners,
        unload: new Listeners}},
    timers: window,
    version: chrome.app.getDetails().version};
}();
