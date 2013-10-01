// API for content scripts

const api = function() {
  // TODO: 'use strict'; after working around global definitions in eval’d scripts below
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
    dev: self.options.dev,
    mutationsFirePromptly: false,
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
          self.port.on(type, handlers[type]);
        }
        self.port.emit("api:handling", Object.keys(handlers));
      }},
    require: function(paths, callback) {
      var callbackId = nextCallbackId++;
      callbacks[callbackId] = callback;
      self.port.emit("api:require", paths, callbackId);
    },
    url: function(path) {
      return self.options.dataUriPrefix + path;
    }};
}();

function log() {
  'use strict';
  var d = new Date, ds = d.toString();
  for (var args = Array.slice(arguments), i = 0; i < args.length; i++) {
    var arg = args[i];
    if (typeof arg == "object") {
      try {
        args[i] = JSON.stringify(arg);
      } catch (e) {
        args[i] = String(arg) + "{" + Object.keys(arg).join(",") + "}";
      }
    }
  }
  args.unshift("'" + ds.substr(0,2) + ds.substr(15,9) + "." + String(+d).substr(10) + "'");
  return console.log.apply.bind(console.log, console, args);
}

/^Mac/.test(navigator.platform) && api.require('styles/mac.css', api.noop);
