// API for content scripts

api = {
  load: function(path, callback) {
    var req = new XMLHttpRequest();
    req.open("GET", api.url(path), true);
    req.onreadystatechange = function() {
      if (req.readyState == 4 && req.status == 200) {
        callback(req.responseText);
      }
    };
    req.send(null);
  },
  log: function(message) {
    var d = new Date(), ds = d.toString();
    console.log.apply(console, Array.prototype.concat.apply(["[" + ds.substring(0, 2) + ds.substring(15,24) + "." + String(+d).substring(10) + "]"], arguments));
  },
  noop: function() {},
  port: {
    emit: function(type, data, callback) {
      if (!callback && typeof data == "function") {
        callback = data, data = null;
      }
      chrome.extension.sendMessage([type, data], callback || api.noop);
    },
    on: function(handlers) {
      chrome.extension.onMessage.addListener(function(msg, sender, respond) {
        var handler = handlers[msg && msg[0]];
        if (handler) {
          api.log("[onMessage] handling:", msg, "from:", sender);
          try {
            return handler(msg[1], respond);
          } catch (e) {
            api.log.error(e, "onMessage");
          } finally {
            api.log("[onMessage] done:", msg);
          }
        } else {
          api.log("[onMessage] ignoring:", msg, "from:", sender);
        }
      });
    }},
  url: chrome.extension.getURL.bind(chrome.extension)};

api.log.error = function(exception, context) {
  console.error((context ? "[" + context + "] " : "") + exception);
  console.error(exception.stack);
};
