// API for content scripts

const api = (function () {
  // TODO: 'use strict'; after working around global definitions in eval’d scripts below
  var msgHandlers = [], nextCallbackId = 1, callbacks = {}, identity = {};

  function onApiRespond(callbackId, response) {
    var cb = callbacks[callbackId];
    log('[api:respond]', cb && cb[0] || '', response != null && (response.length || 0) < 200 ? response : '');
    if (cb) {
      delete callbacks[callbackId];
      cb[1](response);
    }
  }

  function onApiInject(styles, scripts, callbackId) {
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
  }

  const onDetach = function () {
    log = api.noop;  // logging in onDetach sometimes causes Error: Permission denied to access property 'console'
    end();
  };

  self.port.on('api:respond', onApiRespond);
  self.port.on('api:inject', onApiInject);
  self.port.on('detach', onDetach);

  function end() {
    self.port.removeListener('api:respond', onApiRespond);
    self.port.removeListener('api:inject', onApiInject);
    self.port.removeListener('detach', onDetach);
    api.port.on = api.port.emit = api.noop;
    for (let i = msgHandlers.length; i--;) {
      let handlers = msgHandlers[i];
      for (let type in handlers) {
        self.port.removeListener(type, handlers[type]);
      }
    }
    msgHandlers.length = 0;
    for (let i in api.onEnd) {
      try {
        api.onEnd[i]();
      } catch (e) {
        log('[end] onEnd error', e);
      }
    }
    api.onEnd.length = 0;
    callbacks = {};
  }

  function onWinMessage(e) {
    if (e.data && e.data.kifi === 'identity' && e.data.name === identity.name && e.data.bornAt > identity.bornAt && e.origin === location.origin) {
      log('[identity] ending', identity, e.data.bornAt);
      end();
    }
  }

  return {
    dev: self.options.dev,
    mutationsFirePromptly: false,
    identify: function (name) {
      var now = Date.now();
      identity = {name: name, bornAt: now};
      window.postMessage({kifi: 'identity', name: name, bornAt: now}, location.origin);
      window.addEventListener('message', onWinMessage);
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
}());

var log = function log() {
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
};
