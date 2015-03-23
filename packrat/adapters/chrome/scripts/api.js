// API for content scripts

var api = api || function () {
  'use strict';
  var msgHandlers = [], callbacks = {}, nextCallbackId = 1, port;

  function createPort() {
    port = chrome.runtime.connect({name: ''});
    port.onMessage.addListener(function(msg) {
      var kind = msg[0];
      switch (kind) {
      case 'api:respond':
        var id = msg[1], cb = callbacks[id];
        log('[api:respond]', cb && cb[0] || '', msg[2] != null ? msg[2] : '');
        if (cb) {
          delete callbacks[id];
          cb[1](msg[2]);
        }
        break;
      case 'api:injected':
        // markInjected(msg[1]);
        break;
      case 'api:log':
        var enable = msg[1], buf = log.buffer;
        if (!enable && !buf) {
          log.buffer = [];
        } else if (enable && buf) {
          log.buffer = null;
          for (var i = 0; i < buf.length; i++) {
            var o = buf[i];
            log.apply(o.d, o.args);
          }
        }
        break;
      default:
        var data = msg[1], handler;
        log('[onMessage]', kind, data != null ? data : '');
        for (var i in msgHandlers) {
          if (handler = msgHandlers[i][kind]) {
            handler(data);
          }
        }
      }
    });
    port.onDisconnect.addListener(function() {
      log('[onDisconnect]');
      api.port.on = api.port.emit = api.noop;
      for (var i in api.onEnd) {
        api.onEnd[i]();
      }
      api.onEnd.length = msgHandlers.length = 0;
    });
  }

  function markInjected(paths) {
    for (var o = api.injected, i = 0; i < paths.length; i++) {
      var path = paths[i];
      o[path] = (o[path] || 0) + 1;
    }
  }

  var requireQueue;
  function requireNow(paths, callback) {
    if (typeof paths == 'string' ? api.injected[paths] : !(paths = paths.filter(notInjected)).length) {
      done();
    } else {
      requireQueue = requireQueue || [];
      api.port.emit('api:require', {paths: paths, injected: api.injected}, function(paths) {
        markInjected(paths);
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
  function notInjected(path) {
    return !api.injected[path];
  }
  function noop() {
  }

  return {
    identify: noop,
    injected: {'scripts/api.js': 1},
    mutationsFirePromptly: true,
    noop: noop,
    onEnd: [],
    port: {
      emit: function(type, data, callback) {
        if (!callback && typeof data == 'function') {
          callback = data, data = null;
        }
        if (callback) {
          var callbackId = nextCallbackId++;
          callbacks[callbackId] = [type, callback];
        }
        port || createPort();
        port.postMessage([type, data, callbackId]);
      },
      on: function(handlers) {
        if (msgHandlers.indexOf(handlers) < 0) {
          msgHandlers.push(handlers);
          api.port.emit('api:handling', Object.keys(handlers));
        }
      },
      off: function(handlers) {
        for (var i = msgHandlers.length; i--;) {
          if (msgHandlers[i] === handlers) {
            msgHandlers.splice(i, 1);
          }
        }
      }},
    require: function(paths, callback) {
      if (requireQueue) {
        requireQueue.push([paths, callback]);
      } else {
        requireNow(paths, callback);
      }
    },
    url: chrome.runtime.getURL};
}();

var log = log || function () {
  'use strict';
  function log() {
    var buf = log.buffer;
    if (buf) {
      var i = 0, d = Date.now();
      while (i < buf.length && d - buf[i].d > 5000) {
        i++;
      }
      if (i) {
        log.buffer = buf.slice(i);
      }
      log.buffer.push({d: d, args: Array.prototype.slice.call(arguments)});
    } else {
      var ms = (this || Date.now()) % 1000;
      arguments[0] = (ms < 100 ? ms < 10 ? '00' + ms : '0' + ms : ms) + ' ' + arguments[0];
      console.log.apply(console, arguments);
    }
  }
  log.buffer = [];
  return log;
}();
