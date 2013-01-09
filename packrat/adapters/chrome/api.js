// API for main.js

api = function() {

  function dispatch() {
    var args = arguments;
    this.forEach(function(f) {f.apply(null, args)});
  }

  chrome.pageAction.onClicked.addListener(function(tab) {
    api.log("[pageAction.onClicked]", tab);
    dispatch.call(api.icon.on.click, tab);
  });

  chrome.runtime.onStartup.addListener(function() {
    api.log("[onStartup]");
    api.loadReason = "startup";
    dispatch.call(api.on.startup);
  });

  chrome.runtime.onInstalled.addListener(function(details) {
    api.log("[onInstalled] details:", details);
    api.loadReason = details.reason;
    dispatch.call(api.on[details.reason]);
  });

  chrome.tabs.onActivated.addListener(function(info) {
    api.log("[onActivated] tab info:", info);
    chrome.tabs.get(info.tabId, function(tab) {
      try {
        chrome.windows.get(info.windowId, function(win) {
          if (win.type == "normal") {  // ignore popups, etc.
            dispatch.call(api.tabs.on.activate, tab);
          }
        });
      } catch (e) {/* no window */}
    });
  });

  chrome.tabs.onUpdated.addListener(function(tabId, change, tab) {
    api.log("[onUpdated] tab:", tabId, "change:", change);
    if (/^https?:/.test(tab.url)) {
      if (change.url) try {
        chrome.windows.get(tab.windowId, function(win) {
          if (win.type == "normal") {
            dispatch.call(api.tabs.on.navigate, tab);
          }
        });
      } catch (e) {/* no window */}
      if (change.status === "complete") try {
        chrome.windows.get(tab.windowId, function(win) {
          if (win.type == "normal") {
            dispatch.call(api.tabs.on.load, tab);
          }
        });
      } catch (e) {/* no window */}
    }
  });

  var api = {
    browserVersion: navigator.userAgent.replace(/^.*(Chrom[ei][^ ]*).*$/, "$1"),
    icon: {
      on: {
        click: []}},
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
          if (tabId == popupTabId && changed.status == "loading") {
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
        chrome.extension.onMessage.addListener(function(msg, sender, respond) {
          var handler = handlers[msg && msg[0]];
          var tab = sender.tab;
          if (handler) {
            api.log("[onMessage] handling:", msg, "from:", tab && tab.id);
            try {
              return handler(msg[1], respond, tab);
            } catch (e) {
              api.log.error(e);
            } finally {
              api.log("[onMessage] done:", msg);
            }
          } else {
            api.log("[onMessage] ignoring:", msg, "from:", tab && tab.id);
          }
        });
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
    scripts: {
      register: function() {

      }},
    storage: localStorage,
    tabs: {
      emit: function(tabId, type, data) {
        api.log("[api.tabs.emit] tab:", tabId, "type:", type, "data:", data);
        chrome.tabs.sendMessage(tabId, [type, data]);
      },
      inject: function(tabId, details, callback) {
        api.log("[api.tabs.inject] details:", details);
        injectAll(chrome.tabs.insertCSS.bind(chrome.tabs), details.styles, function() {
          injectAll(chrome.tabs.executeScript.bind(chrome.tabs), details.scripts, function() {
            if (details.script) {
              chrome.tabs.executeScript(tabId, {code: details.script}, function(arr) {
                callback(arr && arr[0]);
              });
            } else {
              callback();
            }
          });
        });

        function injectAll(inject, paths, callback) {
          if (paths && paths.length) {
            var n = 0;
            paths.forEach(function(path) {
              api.log("[api.tabs.inject] tab:", tabId, path);
              inject(tabId, {file: path}, function() {
                if (++n == paths.length) {
                  callback();
                }
              });
            });
          } else {
            callback();
          }
        }
      },
      on: {
        activate: [],
        load: [],
        navigate: []}
    },
    timers: window,
    version: chrome.app.getDetails().version};

   api.log.error = function(exception, context) {
    console.error((context ? "[" + context + "] " : "") + exception);
    console.error(exception.stack);
  };

  return api;
}();
