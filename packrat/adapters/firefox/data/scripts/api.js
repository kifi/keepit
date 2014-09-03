// API for content scripts

const api = function() {
  // TODO: 'use strict'; after working around global definitions in eval’d scripts below
  var msgHandlers = [], nextCallbackId = 1, callbacks = {};

  self.port.on('api:respond', function (callbackId, response) {
    var cb = callbacks[callbackId];
    log('[api:respond]', cb && cb[0] || '', response != null && (response.length || 0) < 200 ? response : '');
    if (cb) {
      delete callbacks[callbackId];
      cb[1](response);
    }
  });

  self.port.on('api:inject', function(styles, scripts, callbackId) {
    styles.forEach(function (path) {
      var el = document.createElement('link');
      el.rel = 'stylesheet';
      el.href = api.url(path);
      el.addEventListener('load', onLoad);
      (document.head || document.body).appendChild(el);
    });
    scripts.forEach(function (js) {
      window.eval(js);
    });
    var loadsLeft = styles.length;
    if (loadsLeft === 0) {
      invokeCallback();
    }
    function onLoad() {
      if (--loadsLeft === 0) {
        invokeCallback();
      }
    }
    function invokeCallback() {
      var cb = callbacks[callbackId];
      if (cb) {
        delete callbacks[callbackId];
        cb();
      }
    }
  });

  return {
    dev: self.options.dev,
    mutationsFirePromptly: false,
    noop: function() {},
    onEnd: [],  // TODO: find an event that will allow us to invoke these
    port: {
      emit: function(type, data, callback) {
        if (!callback && typeof data == "function") {
          callback = data, data = null;
        }
        if (callback) {
          var callbackId = nextCallbackId++;
          callbacks[callbackId] = [type, callback];
        }
        self.port.emit(type, data, callbackId);
      },
      on: function(handlers) {
        if (msgHandlers.indexOf(handlers) < 0) {
          msgHandlers.push(handlers);
          for (var type in handlers) {
            self.port.on(type, handlers[type]);
          }
          self.port.emit("api:handling", Object.keys(handlers));
        }
      },
      off: function(handlers) {
        for (var i = msgHandlers.length; i--;) {
          if (msgHandlers[i] === handlers) {
            msgHandlers.splice(i, 1);
            for (var type in handlers) {
              self.port.removeListener(type, handlers[type]);
            }
          }
        }
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
  console.log.apply(console, args);
}
