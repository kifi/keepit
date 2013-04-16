// API for content scripts

api = function() {
  var msgListeners = [];
  var api = {
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
    var d = new Date, ds = d.toString();
    arguments[0] = "[" + ds.substr(0, 2) + ds.substr(15,9) + "." + String(+d).substr(10) + "] " + arguments[0];
    console.log.apply(console, arguments);
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
      msgListeners.push(f);
      chrome.extension.onMessage.addListener(f);
      function f(msg, sender, respond) {
        if (msg && msg.length && msg[0] === lifeId) {
          var kind = msg[1], handler = handlers[kind];
          if (handler) {
            var data = msg[2];
            api.log("[onMessage]", kind, data != null ? data : "");
            try {
              handler(data);
            } catch (e) {
              api.log.error(e, "onMessage");
            }
          }
        }
      }
    }},
  require: function(path, callback) {
    if (injected[path]) {
      callback();
    } else {
      api.port.emit("api:require", path, callback);
    }
  },
  url: chrome.extension.getURL.bind(chrome.extension)};

  api.log.error = function(exception, context) {
    console.error((context ? "[" + context + "] " : "") + exception);
    console.error(exception.stack);
  };

  window.addEventListener("kifiunload", function f(e) {
    if (e.lifeId !== lifeId) {
      api.log("\u2205 end life:", lifeId);
      window.removeEventListener("kifiunload", f);
      msgListeners.forEach(function(ml) {
        chrome.extension.onMessage.removeListener(ml);
      });
      api.port.emit = api.noop;
    }
  });

  return api;
}();

api.log("\u2058 new life:", lifeId);
