// API for content scripts

api = function() {
  var nextCallbackId = 1, callbacks = {};

  function invokeCallback(callbackId, response) {
    var cb = callbacks[callbackId];
    if (cb) {
      delete callbacks[callbackId];
      cb(response);
    }
  }

  self.port.on("api:respond", invokeCallback);

  self.port.on("api:inject", function(styles, scripts, callbackId) {
    styles.forEach(function(css) {
      var el = document.createElement("style");
      el.innerHTML = css;
      (document.head || document.body).appendChild(el);
    });
    scripts.forEach(function(js) {
      window.eval(js);
    });
    invokeCallback(callbackId);
  });

  return {
    load: function(path, callback) {
      api.port.emit("api:load", path, callback);
    },
    log: function() {
      var d = new Date, ds = d.toString();
      var args = Array.prototype.slice.apply(arguments);
      for (var i = 0; i < args.length; i++) {
        var arg = args[i];
        if (typeof arg == "object") {
          args[i] = JSON.stringify(arg);
        }
      }
      console.log("'" + ds.substr(0,2) + ds.substr(15,9) + "." + String(+d).substr(10) + "'", args.join(" "));
    },
    noop: function() {},
    onEnd: [],  // TODO: find an event that will allows us to invoke these
    port: {
      emit: function(type, data, callback) {
        if (!callback && typeof data == "function") {
          callback = data, data = null;
        }
        if (callback) {
          var callbackId = nextCallbackId++;
          callbacks[callbackId] = callback;
        }
        self.port.emit(type, data, callbackId);
      },
      on: function(handlers) {
        for (var type in handlers) {
          if (handlers.hasOwnProperty(type)) {
            self.port.on(type, handlers[type]);
          }
        }
      }},
    require: function(path, callback) {
      var callbackId = nextCallbackId++;
      callbacks[callbackId] = callback;
      self.port.emit("api:require", path, callbackId);
    },
    url: function(path) {
      return self.options.dataUriPrefix + path;
    }};
}();
