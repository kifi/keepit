// API for main.js

api = function() {
  var t0 = +new Date;

  function dispatch() {
    var args = arguments;
    this.forEach(function(f) {f.apply(null, args)});
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
    var lastActivePage = activePages[info.windowId];
    if (lastActivePage) { // ignore popups, etc.
      lastActivePage.active = false;
      var page = pages[info.tabId];
      if (page && /^https?:/.test(page.url)) {
        page.active = true;
        activePages[info.windowId] = page;
        dispatch.call(api.tabs.on.activate, page);
      } else {
        // new tab, no url yet
        activePages[info.windowId] = {};
      }
    }
  });

  chrome.tabs.onUpdated.addListener(function(tabId, change, tab) {
    api.log("[onUpdated] tab:", tabId, "change:", change);
    if (activePages[tab.windowId]) {
      var page = pages[tabId];
      if (change.url && change.url !== (page && page.url)) {
        page = pages[tabId] = {
          id: tabId,
          url: tab.url,
          active: tab.active,
          ready: change.status === "complete"};
        if (/^https?:/.test(page.url)) {
          dispatch.call(api.tabs.on.loading, page);
        }
      }
      // would be nice to get "interactive" too. see http://crbug.com/169070
      if (change.status === "complete") {
        if (page) {
          page.ready = true;
          if (/^https?:/.test(page.url)) {
            dispatch.call(api.tabs.on.complete, page);
          }
        } else {
          api.log.error(Error("no page for " + tabId), "onUpdated");
        }
      }
    }
  });

  chrome.tabs.onRemoved.addListener(function(tabId) {
    delete pages[tabId];
  });

  chrome.windows.onCreated.addListener(function(win) {
    if (win.type === "normal") {
      activePages[win.id] = {};
    }
  });

  chrome.windows.onRemoved.addListener(function(windowId) {
    delete activePages[windowId];
  });

  var pages = {};  // by tab.id
  var activePages = {};  // in "normal" windows only, by window.id
  chrome.tabs.query({windowType: "normal"}, function(tabs) {
    tabs.forEach(function(tab) {
      var page = pages[tab.id];
      if (tab.url !== (page && page.url)) {
        page = pages[tab.id] = {
          id: tab.id,
          url: tab.url,
          active: tab.active,
          ready: tab.status === "complete"};
      }
      if (page.active) {
        activePages[tab.windowId] = page;
      }
      if (/^https?:/.test(tab.url)) {
        // Note: intentionally not dispatching api.tabs.on.ready after injecting content scripts
        if (page.ready) {
          injectContentScripts(page, api.noop);
        } else if (tab.status === "loading") {
          chrome.tabs.executeScript(tab.id, {code: "document.readyState"}, function(arr) {
            page.ready = !!(page.ready || arr && arr[0]);
            if (page.ready) {
              injectContentScripts(page, api.noop);
            }
          });
        }
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
        page.ready = true;
        injectContentScripts(tab, function() {
          dispatch.call(api.tabs.on.ready, page);
        });
      }
    } else if (portHandlers) {
      var handler = portHandlers[msg[0]];
      if (handler) {
        api.log("[onMessage] handling:", msg, "from:", tabId);
        try {
          return handler(msg[1], respond, page);
        } catch (e) {
          api.log.error(e);
        } finally {
          api.log("[onMessage] done:", msg);
        }
      } else {
        api.log("[onMessage] ignoring:", msg, "from:", tabId);
      }
    }
  });

  function injectContentScripts(tab, callback) {
    // for ignoring messages from future updates/installs/reloads of this extension
    chrome.tabs.executeScript(tab.id,
      {code: "!function(e){e.initEvent('kifiunload');dispatchEvent(e)}(document.createEvent('Event'));lifeId=" + t0});

    for (var i = 0, n = 0, N = 0; i < meta.contentScripts.length; i++) {
      var cs = meta.contentScripts[i];
      if (cs[1].test(tab.url)) {
        N++;
        api.tabs.inject(tab.id, cs[0], function() {
          if (++n === N) {
            callback();
          }
        });
      }
    }
    if (N === 0) callback();
  }

  var api = {
    browserVersion: navigator.userAgent.replace(/^.*(Chrom[ei][^ ]*).*$/, "$1"),
    icon: {
      get: function(tabId) {
        return pages[tabId].icon;
      },
      on: {click: []},
      set: function(tabId, path) {
        pages[tabId].icon = path;
        chrome.pageAction.setIcon({tabId: tabId, path: path});
        chrome.pageAction.show(tabId);
      }},
    loadReason: "enable",  // assuming "enable" by elimination
    log: function() {
      var d = new Date(), ds = d.toString();
      console.log.apply(console, Array.prototype.concat.apply(["[" + ds.substring(0, 2) + ds.substring(15,24) + "." + String(+d).substring(10) + "]"], arguments));
    },
    on: {
      install: [],
      update: [],
      startup: []},
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
    storage: localStorage,
    tabs: {
      each: function(callback) {
        Object.keys(pages)
        .map(function(tabId) { return pages[tabId]; })
        .filter(function(page) { return /^https?:/.test(page.url); })
        .forEach(callback);
      },
      emit: function(tabId, type, data) {
        api.log("[api.tabs.emit] tab:", tabId, "type:", type, "data:", data);
        chrome.tabs.sendMessage(tabId, [t0, type, data]);
      },
      get: function(tabId) {
        return pages[tabId];
      },
      inject: function(tabId, path, callback) {
        var page = pages[tabId];
        page.injected = page.injected || {};
        var o = deps(path, page.injected);
        injectAll(chrome.tabs.insertCSS.bind(chrome.tabs), o.styles, function() {
          if (pages[tabId] === page) {  // tab may have navigated or closed
            injectAll(chrome.tabs.executeScript.bind(chrome.tabs), o.scripts, callback);
          }
        });

        function injectAll(inject, paths, callback) {
          var n = 0, N = paths.length;
          if (N) {
            paths.forEach(function(path) {
              if (!page.injected[path]) {
                page.injected[path] = true;
                api.log("[api.tabs.inject] tab:", tabId, path);
                inject(tabId, {file: path}, function() {
                  if (++n === N) {
                    callback();
                  }
                });
              } else if (++n === N) {
                callback();
              }
            });
          } else {
            callback();
          }
        }
      },
      on: {
        activate: [],
        loading: [],
        ready: [],
        complete: []}
    },
    timers: window,
    version: chrome.app.getDetails().version};

  api.log.error = function(exception, context) {
    var d = new Date(), ds = d.toString();
    console.error("[" + ds.substring(0, 2) + ds.substring(15,24) + "." + String(+d).substring(10) + "]" + (context ? "[" + context + "] " : ""), exception.message, exception.stack);
  };

  return api;
}();
