// API for content scripts

api = function() {
  var nextCallbackId = 1, callbacks = {};

  self.port.on("api_response", function(callbackId, response) {
    var cb = callbacks[callbackId];
    if (cb) {
      delete callbacks[callbackId];
      cb[0](response);
    }
  });

  self.port.on("inject", function(styles, scripts, callbackId) {
    styles.forEach(function(css) {
      var el = document.createElement("style");
      el.innerHTML = css;
      (document.head || document.body).appendChild(el);
    });
    scripts.forEach(eval);
    self.port.emit("api_response", callbackId);
  });

  return {
    load: function(path, callback) {
      api.port.emit("api_load", path, callback);
    },
    log: function() {
      var d = new Date(), ds = d.toString();
      console.log.apply(console, Array.prototype.concat.apply(["|" + ds.substring(0, 2) + ds.substring(15,24) + "." + String(+d).substring(10) + "|"], arguments));
    },
    port: {
      emit: function(type, data, callback) {
        if (!callback && typeof data == "function") {
          callback = data, data = null;
        }
        if (callback) {
          var callbackId = nextCallbackId++;
          callbacks[callbackId] = [callback, new Date().getTime()];
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
    url: function(path) {
      return self.options.dataUriPrefix + path;
    }};
}();

api.log.error = function(exception, context) {
  console.error((context ? "[" + context + "] " : "") + exception);
  console.error(exception.stack);
};
