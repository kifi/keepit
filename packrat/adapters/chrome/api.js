// API for main.js

api = function() {
  var t0 = +new Date;

  function dispatch() {
    var args = arguments;
    this.forEach(function(f) {f.apply(null, args)});
  }

  function newPage(tab) {
    return {
      id: tab.id,
      url: tab.url,
      ready: undefined,  // ready state not known
      complete: tab.status === "complete"};
  }

  function createPageAndInjectContentScripts(tab, suppressOnReady) {
    var page = pages[tab.id] = newPage(tab);
    if (tab.active) {
      selectedTabPages[tab.windowId] = page;
    }
    if (/^https?:/.test(tab.url)) {
      if (page.complete) {
        injectContentScripts(page, suppressOnReady);
      } else if (tab.status === "loading") {
        // we might want to dispatch on.loading here.
        chrome.tabs.executeScript(tab.id, {code: "document.readyState", runAt: "document_start"}, function(arr) {
          page.complete = arr && arr[0] === "complete";
          if (page.complete || arr && arr[0] === "interactive") {
            injectContentScripts(page, suppressOnReady);
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

  chrome.tabs.onActivated.addListener(function(info) {
    api.log("[onActivated] tab info:", info);
    var lastPage = selectedTabPages[info.windowId];
    if (lastPage) { // ignore popups, etc.
      if (/^https?:/.test(lastPage.url)) {
        dispatch.call(api.tabs.on.blur, lastPage);
      } else if (lastPage.id) {
        // Chrome Instant search feature sometimes silently destroys chrome://newtab tabs (crbug.com/88458)
        chrome.tabs.get(lastPage.id, function(tab) {
          if (!tab) {
            api.log("[onActivated] freeing lost tab page:", lastPage.id, lastPage.url);
            delete pages[lastPage.id];
          }
        });
      }
      var page = pages[info.tabId];
      if (page) {
        selectedTabPages[info.windowId] = page;
        if (/^https?:/.test(page.url)) {
          dispatch.call(api.tabs.on.focus, page);
        }
      } else {
        // selectedTabPages[info.windowId] = page = pages[info.tabId] = {id: info.tabId};
        chrome.tabs.get(info.tabId, function(tab) {
          if (/^https?:\/\/www.google.com\/webhp\?sourceid=chrome-instant&/.test(tab && tab.url)) {  // TODO: support all Google domains/locales
            api.log("[onActivated] Instant results page:", tab.id, "url:", tab.url);
            createPageAndInjectContentScripts(tab);
          }
        });
      }
    }
  });

  chrome.tabs.onUpdated.addListener(function(tabId, change, tab) {
    api.log("#666", "[onUpdated] %i change: %o", tabId, change);
    if (selectedTabPages[tab.windowId]) {  // window is "normal"
      if (change.status === "loading") {
        var page = pages[tabId];
        if (page && /^https?:/.test(page.url)) {
          dispatch.call(api.tabs.on.unload, page);
        }
        page = pages[tabId] = newPage(tab);
        if (tab.active) {
          selectedTabPages[tab.windowId] = page;
        }
        if (/^https?:/.test(page.url)) {
          dispatch.call(api.tabs.on.loading, page);
        }
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
          api.log.error(Error("no page for " + tabId), "onUpdated");
        }
      }
    }
  });

  chrome.tabs.onRemoved.addListener(function(tabId) {
    var page = pages[tabId];
    if (page) {
      delete pages[tabId];
      if (/^https?:/.test(page.url)) {
        dispatch.call(api.tabs.on.unload, page);
      }
    }
  });

  chrome.windows.onCreated.addListener(function(win) {
    if (win.type === "normal") {
      selectedTabPages[win.id] = {};
    }
  });

  chrome.windows.onRemoved.addListener(function(winId) {
    delete selectedTabPages[winId];
  });

  var focusedWinId, topNormalWinId;
  chrome.windows.getLastFocused(null, function(win) {
    focusedWinId = win.focused ? win.id : chrome.windows.WINDOW_ID_NONE;
    topNormalWinId = win.type == "normal" ? win.id : chrome.windows.WINDOW_ID_NONE;
  });
  chrome.windows.onFocusChanged.addListener(function(winId) {
    api.log("[onFocusChanged] win %o -> %o", focusedWinId, winId);
    if (focusedWinId > 0) {
      var page = selectedTabPages[focusedWinId];
      if (page && /^https?:/.test(page.url)) {
        dispatch.call(api.tabs.on.blur, page);
      }
    }
    focusedWinId = winId;
    if (winId !== chrome.windows.WINDOW_ID_NONE) {
      if (selectedTabPages[winId]) {
        topNormalWinId = winId;
      }
      var page = selectedTabPages[winId];
      if (page && /^https?:/.test(page.url)) {
        dispatch.call(api.tabs.on.focus, page);
      }
    }
  });

  var pages = {};  // by tab.id
  var selectedTabPages = {};  // in "normal" windows only, by window.id (allows us to avoid some async chrome API calls)
  chrome.tabs.query({windowType: "normal"}, function(tabs) {
    tabs.forEach(function(tab) {
      if (!pages[tab.id]) {
        createPageAndInjectContentScripts(tab, true);
      }
    });
  });

  var portHandlers;
  chrome.extension.onMessage.addListener(function(msg, sender, respond) {
    var tab = sender.tab, tabId = tab && tab.id, page = pages[tabId];
    if (tab && page && tab.url !== page.url) {
      api.log.error(Error("url mismatch:\n" + tab.url + "\n" + page.url), "onMessage");
    }
    if (msg === "api:dom_ready") {
      if (page) {
        injectContentScripts(page);
      } else {
        chrome.windows.get(tab.windowId, function(win) {
          if (win && win.type == "normal") {
            api.log.error(Error("no page for " + tabId), "api:dom_ready");
          }
        });
      }
    } else if (msg[0] === "api:require") {
      if (tab) {
        injectWithDeps(tab.id, msg[1], respond);
        return true;
      }
    } else if (portHandlers) {
      var handler = portHandlers[msg[0]];
      if (handler) {
        api.log("#0a0", "[onMessage] %i %s %o", tabId, msg[0], msg[1]);
        try {
          return handler(msg[1], respond, page);
        } catch (e) {
          api.log.error(e);
        }
      } else {
        api.log("#c00", "[onMessage] ignoring:", msg, "from:", tabId);
      }
    }
  });

  function injectContentScripts(page, suppressOnReady) {
    if (page.injecting || page.ready) return;

    page.injecting = true;

    // for ignoring messages from future updates/installs/reloads of this extension
    chrome.tabs.executeScript(page.id, {
      code: "lifeId=" + t0 + ";!function(e){e.initEvent('kifiunload');e.lifeId=lifeId;dispatchEvent(e)}(document.createEvent('Event'))",
      runAt: "document_start"});

    for (var i = 0, n = 0, N = 0; i < meta.contentScripts.length; i++) {
      var cs = meta.contentScripts[i];
      if (cs[1].test(page.url)) {
        N++;
        injectWithDeps(page.id, cs[0], function() {
          if (++n === N) {
            done();
          }
        });
      }
    }
    if (N === 0) done();

    function done() {
      page.ready = true;
      delete page.injecting;
      if (!suppressOnReady) {
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

  var hostRe = /^https?:\/\/[^\/]*/, hexRe = /^#[0-9a-f]{3}$/i;
  var api = {
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
    browserVersion: navigator.userAgent.replace(/^.*(Chrom[ei][^ ]*).*$/, "$1"),
    icon: {
      on: {click: new Listeners},
      set: function(tab, path) {
        if (tab === pages[tab.id]) {
          tab.icon = path;
          chrome.pageAction.setIcon({tabId: tab.id, path: path});
          chrome.pageAction.show(tab.id);
        }
      }},
    // internal: {
    //   pages: pages,
    //   selectedTabPages: selectedTabPages
    // },
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
        if (portHandlers) throw Error("api.port.on already called");
        portHandlers = handlers;
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
    socket: {
      open: function(url, handlers) {
        var callbacks = {}, nextCallbackId = 1;  // TODO: garbage collect old uncalled callbacks
        var socket = new ReconnectingWebSocket(url, function(e) {
          var msg = JSON.parse(e.data);
          if (Array.isArray(msg)) {
            var id = msg.shift();
            if (id > 0) {
              var cb = callbacks[id];
              if (cb) {
                api.log("#0ac", "[socket.receive] calling back after", new Date - cb[1], "ms:", id);
                delete callbacks[id];
                cb[0].apply(null, msg);
              } else {
                api.log("#0ac", "[socket.receive] ignoring, no callback", [id].concat(msg));
              }
            } else {
              var handler = handlers[id];
              if (handler) {
                api.log("#0ac", "[socket.receive] invoking handler", id);
                handler.apply(null, msg);
              } else {
                api.log("#0ac", "[socket.receive] ignoring, no handler", [id].concat(msg));
              }
            }
          } else {
            api.log("#0ac", "[socket.receive] ignoring (not array):", msg);
          }
        });
        return {
          send: function(arr, callback) {
            if (callback) {
              var id = nextCallbackId++;
              callbacks[id] = [callback, +new Date];
              arr.splice(1, 0, id);
            }
            api.log("#0ac", "[socket.send]", arr);
            socket.send(JSON.stringify(arr));
          },
          close: function() {
            socket.close();
            this.send = this.close = api.noop;
          }
        };
      }
    },
    storage: localStorage,
    tabs: {
      each: function(callback) {
        Object.keys(pages)
        .map(function(tabId) { return pages[tabId]; })
        .filter(function(page) { return /^https?:/.test(page.url); })
        .forEach(callback);
      },
      emit: function(tab, type, data) {
        var currTab = pages[tab.id];
        if (tab === currTab || currTab && currTab.url.match(hostRe)[0] == tab.url.match(hostRe)[0]) {
          api.log("#0c0", "[api.tabs.emit] %i %s %o", tab.id, type, data);
          chrome.tabs.sendMessage(tab.id, [t0, type, data]);
        } else {
          api.log("[api.tabs.emit] SUPPRESSED %i %s navigated: %s -> %s", tab.id, type, tab.url, currTab && currTab.url);
        }
      },
      get: function(tabId) {
        return pages[tabId];
      },
      getActive: function() {
        var page = selectedTabPages[topNormalWinId];
        return page && /^https?:/.test(page.url) ? page : null;
      },
      isFocused: function(tab) {
        return selectedTabPages[focusedWinId] === tab;
      },
      isSelected: function(tab) {
        return Object.keys(selectedTabPages).some(function(winId) {
          return selectedTabPages[winId] === tab;
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

  api.log.error = function(exception, context) {
    var d = new Date, ds = d.toString();
    console.error("[" + ds.substr(0, 2) + ds.substr(15,9) + "." + String(+d).substr(10) + "]" + (context ? "[" + context + "] " : ""), exception.message, exception.stack);
  };

  return api;
}();
