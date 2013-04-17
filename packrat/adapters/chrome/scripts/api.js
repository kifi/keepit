// API for content scripts

api = function() {
  var msgHandlers = [], callbacks = {}, nextCallbackId = 1;

  var port = chrome.runtime.connect({name: ""});
  port.onMessage.addListener(function(msg) {
    var kind = msg[0];
    if (kind == "api:respond") {
      var id = msg[1], cb = callbacks[id];
      if (cb) {
        delete callbacks[id];
        cb(msg[2]);
      }
    } else {
      var data = msg[1];
      api.log("[onMessage]", kind, data != null ? data : "");
      for (var i in msgHandlers) {
        var handler = msgHandlers[i][kind];
        handler && handler(data);
      }
    }
  });
  port.onDisconnect.addListener(function() {
    api.log("[onDisconnect]");
    api.port = {emit: api.noop};
    for (var i in api.onEnd) {
      api.onEnd[i]();
    }
    api.onEnd.length = msgHandlers.length = 0;
  });

  return {  // TODO: indent below
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
  log: function() {
    var d = new Date, ds = d.toString();
    arguments[0] = "[" + ds.substr(0, 2) + ds.substr(15,9) + "." + String(+d).substr(10) + "] " + arguments[0];
    console.log.apply(console, arguments);
  },
  noop: function() {},
  onEnd: [],
  port: {
    emit: function(type, data, callback) {
      if (!callback && typeof data == "function") {
        callback = data, data = null;
      }
      if (callback) {
        var callbackId = nextCallbackId++;
        callbacks[callbackId] = callback;
      }
      port.postMessage([type, data, callbackId]);
    },
    on: function(handlers) {
      msgHandlers.push(handlers);
    }},
  require: function(path, callback) {
    if (injected[path]) {
      callback();
    } else {
      api.port.emit("api:require", path, callback);
    }
  },
  url: chrome.runtime.getURL};
}();
