// API for main.js

api = function() {
  function dispatch() {
    var args = arguments;
    this.forEach(function(f) {f.apply(null, args)});
  }

  function createPage(tab, skipOnLoading) {
    var page = pages[tab.id] = {
      id: tab.id,
      url: tab.url,
      ready: undefined,  // ready state not known
      complete: tab.status === "complete"};
    if (tab.active) {
      selectedTabIds[tab.windowId] = tab.id;
    }
    if (!skipOnLoading && /^https?:/.test(tab.url)) {
      dispatch.call(api.tabs.on.loading, page);
    }
    return page;
  }

  function createPageAndInjectContentScripts(tab, skipHandlers) {
    var page = createPage(tab, skipHandlers);
    if (/^https?:/.test(tab.url)) {
      if (page.complete) {
        injectContentScripts(page, skipHandlers);
        if (!skipHandlers) {
          dispatch.call(api.tabs.on.complete, page);
        }
      } else if (tab.status === "loading") {
        // we might want to dispatch on.loading here.
        chrome.tabs.executeScript(tab.id, {code: "document.readyState", runAt: "document_start"}, function(arr) {
          page.complete = arr && arr[0] === "complete";
          if (page.complete || arr && arr[0] === "interactive") {
            injectContentScripts(page, skipHandlers);
          }
          if (page.complete && !skipHandlers) {
            dispatch.call(api.tabs.on.complete, page);
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

  var updateCheckRequested;
  chrome.runtime.onUpdateAvailable.addListener(function(details) {
    api.log("#666", "[onUpdateAvailable]", details.version);
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

  chrome.tabs.onUpdated.addListener(function(tabId, change, tab) {
    api.log("#666", "[tabs.onUpdated] %i change: %o", tabId, change);
    if (selectedTabIds[tab.windowId]) {  // window is "normal"
      if (change.status === "loading") {
        onRemoved(tabId);
        createPage(tab);
      }
      // would be nice to get "interactive" too. see http://crbug.com/169070
      if (change.status === "complete") {
        var page = pages[tabId];
        if (page) {
          page.complete = true;
          if (/^https?:/.test(page.url)) {
            injectContentScripts(page);
            dispatch.call(api.tabs.on.complete, page);
          }
        } else {
          api.log("#800", "[onUpdated] %i no page", tabId);
        }
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
    focusedWinId = win.focused ? win.id : chrome.windows.WINDOW_ID_NONE;
    topNormalWinId = win.type == "normal" ? win.id : chrome.windows.WINDOW_ID_NONE;
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
    "api:reload": function() {
      if (!api.isPackaged()) {
        chrome.runtime.reload();
      }
    },
    "api:require": function(data, respond, tab) {
      injectWithDeps(tab.id, data, respond);
    }};
  chrome.runtime.onConnect.addListener(function(port) {
    var tab = port.sender.tab;
    if (port.sender.id === chrome.runtime.id) {
      api.log("[onConnect]", tab.id, tab.url);
      if (ports[tab.id]) {
        api.log("#a00", "[onConnect] %i disconnecting prev port", tab.id);
        ports[tab.id].disconnect();
      }
      ports[tab.id] = port;
      port.onMessage.addListener(function(msg) {
        var kind = msg[0], data = msg[1], callbackId = msg[2];
        var handler = portHandlers[kind];
        if (handler) {
          var page = pages[tab.id];
          if (page) {
            if (tab.url !== page.url) {
              api.log("#a00", "[onMessage] %i %s mismatch:\n%s\n%s", tab.id, kind, tab.url, page.url);
            }
            api.log("#0a0", "[onMessage]", tab.id, kind, data != null ? data : "");
            handler(data, function(response) {
              port.postMessage(["api:respond", callbackId, response]);
            }, page);
          } else {
            api.log("#a00", "[onMessage]", tab.id, kind, "ignored, no page", tab.url);
          }
        } else {
          api.log("#a00", "[onMessage]", tab.id, kind, "ignored");
        }
      });
      port.onDisconnect.addListener(function() {
        api.log("[onDisconnect]");
        delete ports[tab.id];
      });

      var page = pages[tab.id];
      if (page && page.toEmit) {
        if (tab.url !== page.url) {
          api.log("#a00", "[onConnect] %i mismatch:\n%s\n%s", tab.id, tab.url, page.url);
        }
        page.toEmit.forEach(function emit(m) {
          api.log("#0c0", "[onConnect:emit] %i %s %o", tab.id, m[0], m[1] != null ? m[1] : "");
          port.postMessage(m);
        });
        delete page.toEmit;
      }
    }
  });

  chrome.runtime.onMessage.addListener(function(msg, sender) {
    var tab = sender.tab;
    if (sender.id === chrome.runtime.id && tab && /^https?:/.test(tab.url) && msg === "api:dom_ready") {
      var page = pages[tab.id];
      if (page) {
        if (tab.url === page.url) {
          api.log("[api:dom_ready]", tab.id, tab.url);
        } else {
          api.log("#a00", "[api:dom_ready] %i url mismatch:\n%s\n%s", tab.id, tab.url, page.url);
        }
        injectContentScripts(page);
      } else if (selectedTabIds[tab.windowId]) {  // normal win
        api.log("#a00", "[api:dom_ready] no page for", tab.id, tab.url);
      }
    }
  });

  function injectContentScripts(page, skipOnReady) {
    if (page.injecting || page.ready) return;
    if (/^https:\/\/chrome.google.com\/webstore/.test(page.url)) {
      api.log("[injectContentScripts] forbidden", page.url);
      return;
    }
    page.injecting = true;

    if (api.prefs.get("suppressLog")) {
      chrome.tabs.executeScript(page.id, {code: "var api=api||{};api.log=function(){}", runAt: "document_start"}, api.noop);
    }
    for (var i = 0, n = 0, N = 0; i < meta.contentScripts.length; i++) {
      var cs = meta.contentScripts[i];
      if (cs[1].test(page.url)) {
        N++;
        injectWithDeps(page.id, cs[0], function() {
          if (++n === N) {
            if (localStorage[":env"] == "development") {
              chrome.tabs.executeScript(page.id, {code: "api.dev=1", runAt: "document_start"}, api.noop);
            }
            done();
          }
        });
      }
    }
    if (N === 0) done();

    function done() {
      page.ready = true;
      delete page.injecting;
      if (!skipOnReady) {
        dispatch.call(api.tabs.on.ready, page);
      }
    }
  }

  function injectWithDeps(tabId, path, callback) {
    // To avoid duplicate injections, we use one script to both:
    // 1) read what's already been injected and
    // 2) record what's about to be injected.
    // We use runAt: "document_start" to ensure that it executes ASAP.
    var paths = function(o) {return o.styles.concat(o.scripts)}(deps(path));
    chrome.tabs.executeScript(tabId, {
        code: "var injected=injected||{};(function(i){var o={},n=['" + paths.join("','") + "'],k;for(k in i)o[k]=i[k];for(k in n)i[n[k]]=1;return o})(injected)",
        runAt: "document_start"}, function(arr) {
      var injected = arr && arr[0] || {};
      var o = deps(path, injected), n = 0;
      injectAll(chrome.tabs.insertCSS.bind(chrome.tabs), o.styles, done);
      injectAll(chrome.tabs.executeScript.bind(chrome.tabs), o.scripts, done);
      function done() {
        if (++n === 2) callback();
      }
    });

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
          var arg = /^application\/json/.test(this.getResponseHeader("Content-Type")) ? JSON.parse(this.responseText) : this;
          ((this.status == 200 ? done : fail) || api.noop)(arg);
          done = fail = api.noop;  // ensure we don't call a callback again
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
      chrome.runtime.requestUpdateCheck(function(status) {
        api.log("[requestUpdateCheck]", status);
      });
      updateCheckRequested = true;
    },
    socket: {
      open: function(url, handlers, onConnect) {
        var callbacks = {}, nextCallbackId = 1;  // TODO: garbage collect old uncalled callbacks
        var rws = new ReconnectingWebSocket(url, function receive(e) {
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
                api.log("#0ac", "[socket.receive] handling", id);
                handler.apply(null, msg);
              } else {
                api.log("#0ac", "[socket.receive] ignoring", [id].concat(msg));
              }
            }
          } else {
            api.log("#0ac", "[socket.receive] ignoring", msg);
          }
        }, function() {
          socket.seq++;
          onConnect();
        });
        var socket = {
          seq: 0,
          send: function(arr, callback) {
            if (callback) {
              var id = nextCallbackId++;
              callbacks[id] = [callback, +new Date];
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
      emit: function(tab, type, data) {
        var currTab = pages[tab.id];
        if (tab === currTab || currTab && currTab.url.match(hostRe)[0] == tab.url.match(hostRe)[0]) {
          var port = ports[tab.id];
          if (port) {
            api.log("#0c0", "[api.tabs.emit] %i %s %o", tab.id, type, data);
            port.postMessage([type, data]);
          } else {
            (currTab.toEmit || (currTab.toEmit = [])).push([type, data]);
          }
        } else {
          api.log("#a00", "[api.tabs.emit] suppressed %i %s navigated: %s -> %s", tab.id, type, tab.url, currTab && currTab.url);
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
        ready: new Listeners,
        complete: new Listeners,
        unload: new Listeners}},
    timers: window,
    version: chrome.app.getDetails().version};
}();
