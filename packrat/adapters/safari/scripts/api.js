// Safari Content API
var api = api || (function () {
  var msgHandlers = [];
  var callbacks = {};
  var nextCallbackId = 1;
  var portInited = false;

  function initPort() {
    portInited = true;
    safari.self.addEventListener('message', function (e) {
      var msg;
      e.message = e.message;
      if (e.message && e.message.callbackId) {
        msg = [e.name, e.message.callbackId, e.message.data];
      } else {
        msg = [e.name, e.message, e.message];
      }
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
      case 'api:inject':
        injectContentScript(msg[1], msg[2]);
        markInjected(msg[1]);
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
      case 'api:reload':
        window.location.reload();
        break;
      case 'api:disconnect':
        onDisconnect();
        break;
      case 'api:play':
        var src = msg[2].src;
        var el = document.querySelector('audio[data-kifi-play]');
        if (!el) {
          el = document.createElement('audio');
          el.setAttribute('data-kifi-play', '');
          document.body.appendChild(el);
        }
        el.src = src;
        el.play();
        break;
      default:
        var data = msg[2];
        log('[onMessage]', kind, data != null ? data : '');
        msgHandlers.forEach(function (handlers) {
          var handler = handlers[kind];
          if (handler) {
            handler(data);
          }
        });
      }
    });
  }

  function onDisconnect() {
    log('[onDisconnect]');
    safari.self.removeEventListener('message');
    api.port.on = api.port.emit = api.noop;
    api.onEnd.forEach(function (onEnd) {
      onEnd();
    });
    api.onEnd.length = msgHandlers.length = 0;
  }

  function injectContentScript(injected, callbackId) {
    var lazyLoad = eval;
    var {scripts, styles} = injected;
    var loadsLeft = styles.length;

    scripts.forEach(function (js) {
      lazyLoad(js);
    });

    if (loadsLeft === 0) {
      invokeCallback();
    }

    styles.forEach(function (path) {
      var href = api.url(path);
      var el;
      if (document.querySelector('link[href="' + href + '"]') === null) {
        el = document.createElement('link');
        el.rel = 'stylesheet';
        el.type = 'text/css';
        el.href = href;
        el.addEventListener('load', onLoad);
        (document.head || document.body).appendChild(el);
      } else {
        onLoad();
      }
    });

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

  function markInjected(paths) {
    for (var o = api.injected, i = 0; i < paths.length; i++) {
      var path = paths[i];
      o[path] = (o[path] || 0) + 1;
    }
  }

  function noop() {}

  return {
    identify: noop,
    injected: {'scripts/does-not-exist.js': 1},//{'scripts/api.js': 1},
    mutationsFirePromptly: true,
    noop: noop,
    onEnd: [],
    port: {
      emit: function(type, data, callback) {
        var callbackId;
        if (!callback && typeof data === 'function') {
          callback = data;
          data = null;
        }
        if (callback) {
          callbackId = nextCallbackId++;
          callbacks[callbackId] = [type, callback];
        }

        if (!portInited) {
          initPort();
        }

        safari.self.tab.dispatchMessage(type, { data, callbackId });
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
      }
    },
    require: function(paths, callback) {
      var injected = api.injected;
      if (typeof paths === 'string') {
        paths = [paths];
      }
      api.port.emit('api:require', { paths, injected }, function (paths) {
        markInjected(paths.scripts.concat(paths.styles));
        callback();
      });
    },
    url: function(path) {
      return safari.extension.baseURI + path;
    }
  };
}());

var oneOnDOMContentLoaded = once(onDOMContentLoaded);

function once(cb) {
  var called = false;
  return function () {
    if (!called) {
      called = true;
      cb.apply(this, arguments);
    }
  }
}

function onApiConnect() {
  log('[api:onConnect] %s', window.location.href);
  api.port.emit('api:onConnect', { url: window.location.href });

  log('[api:onConnect] visibilityState is %s', document.visibilityState);
  if (document.visibilityState === 'visible') {
    log('[api:onConnect] readyState is %s', document.readyState);
    if (document.readyState === 'complete' || document.readyState === 'loaded') {
      oneOnDOMContentLoaded(false);
    } else {
      document.addEventListener('DOMContentLoaded', oneOnDOMContentLoaded.bind(null, true));
    }
  } else {
    document.addEventListener('visibilitychange', onVisibilityChange);
  }

  window.addEventListener('beforeunload', onBeforeUnload);
  window.addEventListener('unload', onUnload);
}


function onDOMContentLoaded(actual) {
  var injected = api.injected;
  var url = window.location.href;
  var data = { injected, actual, url };
  log('[api:DOMContentLoaded] actual: %s, url: %s, injected: %O', data.actual, data.url, data.injected);
  api.port.emit('api:DOMContentLoaded', data);
}

function onVisibilityChange(e) {
  log('[onVisibilityChange] %O, current visibilityState: %s', e, document.visibilityState);
  if (document.visibilityState === 'visible') {
    oneOnDOMContentLoaded(false);
  }
}

function onBeforeUnload(e) {
  log('[onBeforeUnload] %O', e);
  api.port.emit('api:beforeunload', { url: window.location.href });
}

function onUnload() {
  log('[onUnload] %O', e);
  api.port.emit('api:unload', { url: window.location.href });
}

if (window.top === window) {
  onApiConnect();
}

// var log = log || function () {
//   'use strict';
//   function log() {
//     var buf = log.buffer;
//     if (buf) {
//       var i = 0, d = Date.now();
//       while (i < buf.length && d - buf[i].d > 5000) {
//         i++;
//       }
//       if (i) {
//         log.buffer = buf.slice(i);
//       }
//       log.buffer.push({d: d, args: Array.prototype.slice.call(arguments)});
//     } else {
//       var ms = (this || Date.now()) % 1000;
//       arguments[0] = (ms < 100 ? ms < 10 ? '00' + ms : '0' + ms : ms) + ' ' + arguments[0];
//       console.log.apply(console, arguments);
//     }
//   }
//   log.buffer = [];
//   return log;
// }();
function log(a0) {
  'use strict';
  var hexRe = /^#[0-9a-f]{3}$/i;
  var args = arguments;
  if (hexRe.test(a0)) {
    args[0] = '%c' + args[1];
    args[1] = 'color:' + a0;
  }
  console.log.apply(console, args);
}
