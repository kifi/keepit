function ReconnectingWebSocket(url, opts) {
  'use strict';
  var ws, self = this, outbox = [], timers = {}, lastRecOrPingTime = 0;
  var o = merge({
    openTimeoutMs: 16000,
    helloTimeoutMs: 3000,
    pingAfterMs: 10000,
    pongTimeoutMs: 3000,
    onConnect: noop,
    onMessage: noop,
    onDisconnect: noop
  }, opts);

  this.send = function(data) {
    if (ws && ws.readyState === WebSocket.OPEN && !timers.disconnect) {
      ws.send(data);
      if (Date.now() - lastRecOrPingTime > o.pingAfterMs) {  // a way to recover from timeout failure/unreliability
        ping();
      }
    } else {
      outbox.push([data, Date.now()]);
    }
  };
  this.close = function() {
    log('#0bf', '[RWS.close]');
    disconnect('close');
    this.send = this.close = outbox = null;
  };

  connect();

  function connect(retryPolicy, elapsedRetryDelayMs) {
    log('#0bf', '[RWS.connect]');
    clearTimers();
    ws = new WebSocket(url);
    ws.onopen = onOpen;
    ws.onclose = onClose.bind(null, retryPolicy, elapsedRetryDelayMs);
    ws.onerror = onError;
    timers.disconnect = setTimeout(
      disconnect.bind(null, 'connect', o.openTimeoutMs, retryPolicy, elapsedRetryDelayMs),
      o.openTimeoutMs);  // expecting onOpen
  }

  function disconnect(why, msWaited, retryPolicy, prevRetryDelayMs) {
    var retryDelayMs = why === 'close' ? null : calcRetryConnectDelay[retryPolicy || 'standard'](prevRetryDelayMs);
    log('#0bf', '[RWS.disconnect] why:', why, 'readyState:', ws && ws.readyState, 'outbox:', outbox.length, 'retry:', retryDelayMs || 'no');
    if (ws) {
      ws.onopen = ws.onmessage = ws.onerror = ws.onclose = null;
      ws.close();  // noop if already closed
      ws = null;
    }
    clearTimers();
    if (retryDelayMs) {
      window.addEventListener('online', onOnlineConnect);
      timers.connect = setTimeout(connect.bind(null, retryPolicy, retryDelayMs), retryDelayMs);
      o.onDisconnect(why, msWaited / 1000);
    } else {
      window.removeEventListener('online', onOnlineConnect);
    }
  }

  function onOpen() {
    log('#0bf', '[RWS.onopen]');
    ws.onclose = onClose;
    ws.onmessage = onMessage1;
    clearTimers();
    window.removeEventListener('online', onOnlineConnect);
    timers.disconnect = setTimeout(
      disconnect.bind(null, 'stillborn', o.helloTimeoutMs),
      o.helloTimeoutMs);
  }

  function onClose(retryPolicy, elapsedRetryDelayMs) {
    log('#0bf', '[RWS.onclose]');
    if (elapsedRetryDelayMs) {
      disconnect('onclose', null, retryPolicy, elapsedRetryDelayMs);
    } else {
      disconnect('onclose');
    }
  }

  function onError(e) {
    log('#a00', '[RWS.onerror]', e);
  }

  function onMessage1(e) {
    clearTimers();
    timers.ping = setTimeout(ping, o.pingAfterMs);
    lastRecOrPingTime = Date.now();
    if (e.data === '["hi"]') {
      log('#0bf', '[RWS.onMessage1]', e.data);
      ws.onmessage = onMessageN;
      o.onConnect();
      sendOutboxMessages();
    } else if (e.data === '["denied"]') {
      log('#a00', '[RWS.onMessage1]', e.data);
      disconnect('denied');
    } else if (e.data.lastIndexOf('["bye"', 0) === 0) {
      log('#a00', '[RWS.onMessage1]', e.data);
      disconnect('bye', null, 'polite');
    } else {  // shouldn't happen
      log('#a00', '[RWS.onMessage1] relaying');
      o.onMessage.call(self, e);
    }
  }

  function onMessageN(e) {
    clearTimers();
    timers.ping = setTimeout(ping, o.pingAfterMs);
    lastRecOrPingTime = Date.now();
    if (e.data === '["pong"]') {
      log('#0ac', '[RWS.pong]');
      sendOutboxMessages();
    } else if (e.data.lastIndexOf('["bye"', 0) === 0) {
      log('#0ac', '[RWS.onMessageN]', e.data);
      disconnect('bye', null, 'polite');
    } else {
      o.onMessage.call(self, e);
    }
  }

  function sendOutboxMessages() {
    while (outbox.length) {
      var a = outbox.shift();
      log('#0bf', '[RWS] sending, delayed %i ms: %s', Date.now() - a[1], (/\w+/.exec(a[0]) || a)[0]);
      ws.send(a[0]);
    }
  }

  function onOnlineConnect() {
    log('#0bf', '[RWS.onOnlineConnect]');
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      disconnect('online');  // reattempts connect soon
    }
  }

  function ping() {
    log('#0bf', '[RWS.ping]');
    ws.send('["ping"]');
    lastRecOrPingTime = Date.now();
    clearTimers();
    timers.disconnect = setTimeout(disconnect.bind(null, 'pong', o.pongTimeoutMs), o.pongTimeoutMs);
  }

  function clearTimers() {
    for (var kind in timers) {
      clearTimeout(timers[kind]);
      delete timers[kind];
    }
  }

  var calcRetryConnectDelay = {
    standard: function (prev) {
      return prev ? Math.min(5000, prev * 1.5) : 300;
    },
    polite: function () {
      return 5000 + Math.random() * 15000;
    }
  };

  function merge(o, O) {
    for (var k in O) {
      o[k] = O[k];
    }
    return o;
  }

  function noop() {}
}
