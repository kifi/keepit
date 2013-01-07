// API for main.js

api = function() {

  function dispatch(a) {
    if (a) a.forEach(function(f) {f()});
  }

  chrome.runtime.onStartup.addListener(function() {
    api.loadReason = "startup";
    dispatch(api.on.startup);
  });

  chrome.runtime.onInstalled.addListener(function(details) {
    api.loadReason = details.reason;
    dispatch(api.on[details.reason]);
  });

  return {
    loadReason: "enable",  // assuming "enable" by elimination
    on: {
      install: [],
      update: [],
      startup: []},
    port: {
      on: function(handlers) {
        chrome.extension.onMessage.addListener(function(msg, sender, respond) {
          var handler = handlers[msg && msg.type];
          var tab = sender.tab;
          if (handler) {
            log("[onMessage] handling:", msg, "from:", tab && tab.id);
            try {
              return handler(msg, respond, tab);
            } catch (e) {
              error(e);
            } finally {
              log("[onMessage] done:", msg);
            }
          } else {
            log("[onMessage] ignoring:", msg, "from:", tab && tab.id);
          }
        });
      }
    },
    timers: window,
    version: chrome.app.getDetails().version};
}();
