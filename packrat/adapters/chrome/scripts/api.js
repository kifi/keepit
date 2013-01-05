api = function() {
  var handlers = {};
  function fire(eventType) {
    var arr = handlers[eventType];
    if (arr) {
      arr.forEach(function(f) {f()});
    }
  }

  chrome.runtime.onStartup.addListener(function() {
    fire(api.loadReason = "startup");
  });

  chrome.runtime.onInstalled.addListener(function(details) {
    fire(api.loadReason = details.reason);
  });

  return {
    loadReason: "enable",
    messages: {
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
    off: function(event, callback) {
      var arr = handlers[event];
      if (arr) {
        var i = arr.indexOf(callback);
        if (i >= 0) {
          arr.splice(i, 1);
        }
      }
    },
    on: function(event, callback) {
      var arr = handlers[event];
      if (!arr) {
        handlers[event] = [callback];
      } else {
        arr.push(callback);
      }
    },
    timers: window,
    version: chrome.app.getDetails().version};
}();
