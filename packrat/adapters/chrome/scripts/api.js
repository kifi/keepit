api = {
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
  timers: window};
