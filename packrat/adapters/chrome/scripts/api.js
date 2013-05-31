// API for content scripts

var api = function() {
  var msgHandlers = [], callbacks = {}, nextCallbackId = 1;

  var port = chrome.runtime.connect({name: ""});
  port.onMessage.addListener(function(msg) {
    var kind = msg[0];
    if (kind == "api:respond") {
      var id = msg[1], cb = callbacks[id];
      api.log("[onMessage] response:", msg[2] != null ? msg[2] : "");
      if (cb) {
        delete callbacks[id];
        cb(msg[2]);
      }
    } else if (kind == "api:injected") {
      msg[1].forEach(function(path) {
        injected[path] = true;
      });
      api.log("[api:injected]", Object.keys(injected));
      requireNext();
    } else {
      var data = msg[1], handler;
      api.log("[onMessage]", kind, data != null ? data : "");
      for (var i in msgHandlers) {
        if (handler = msgHandlers[i][kind]) {
          handler(data);
        }
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

  var requireQueue = [], injected = {};
  function requireNow(path, callback) {
    if (injected[path]) {
      done();
    } else {
      requireQueue = requireQueue || [];
      api.port.emit("api:require", {path: path, injected: injected}, function(paths) {
        for (var i = 0; i < paths.length; i++) {
          injected[paths[i]] = true;
        }
        done();
      });
    }
    function done() {
      try {
        callback();
      } finally {
        requireNext();
      }
    }
  }
  function requireNext() {
    if (requireQueue && requireQueue.length) {
      requireNow.apply(null, requireQueue.shift());
    } else {
      requireQueue = null;
    }
  }

  return {
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
    log: api && api.log || function() {
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
      if (requireQueue) {
        requireQueue.push([path, callback]);
      } else {
        requireNow(path, callback);
      }
    },
    url: chrome.runtime.getURL};
}();
