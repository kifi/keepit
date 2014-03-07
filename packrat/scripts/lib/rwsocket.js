function ReconnectingWebSocket(opts) {
  'use strict';
  var idlePingDelayMs = 10000, lastRecOrPingTime = 0;
  var ws, self = this, buffer = [], timers = {};

  this.send = function(data) {
    if (ws && ws.readyState === WebSocket.OPEN && !timers.disconnect) {
      ws.send(data);
      if (Date.now() - lastRecOrPingTime > idlePingDelayMs) {  // a way to recover from timeout failure/unreliability
        ping();
      }
    } else {
      buffer.push([data, Date.now()]);
    }
  };
  this.close = function() {
    log("#0bf", "[RWS.close]")();
    disconnect('close');
    this.send = this.close = buffer = null;
    window.removeEventListener('online', onOnlineConnect);
  };

  connect();

  function connect(retryPolicy, elapsedRetryDelayMs) {
    clearTimers();
    if (navigator.onLine) {
      log('#0bf', '[RWS.connect]')();
      ws = new WebSocket(opts.url);
      ws.onopen = onOpen;
      ws.onclose = onClose;
      ws.onerror = onError;
      timers.disconnect = setTimeout(disconnect.bind(null, 'connect', 20, retryPolicy, elapsedRetryDelayMs), 20000);  // expecting onOpen
    } else {
      log('#0bf', '[RWS.connect] offline')();
      window.addEventListener("online", onOnlineConnect);
    }
  }

  function disconnect(why, secWaited, retryPolicy, prevRetryDelayMs) {
    var retryDelayMs = why === 'close' ? null : calcRetryConnectDelay[retryPolicy || 'standard'](prevRetryDelayMs);
    log('#0bf', '[RWS.disconnect] why:', why, 'readyState:', ws && ws.readyState, 'buffered:', buffer.length, 'retry:', retryDelayMs || 'no')();
    if (ws) {
      ws.onopen = ws.onmessage = ws.onerror = ws.onclose = null;
      ws.close();  // noop if already closed
      ws = null;
    }
    clearTimers();
    if (retryDelayMs) {
      timers.connect = setTimeout(connect.bind(null, retryPolicy, retryDelayMs), retryDelayMs);
      opts.onDisconnect(why, secWaited);
    }
  }

  function onOpen() {
    log('#0bf', '[RWS.onopen]')();
    ws.onmessage = onMessage1;
    clearTimers();
    timers.disconnect = setTimeout(disconnect.bind(null, 'stillborn', 3), 3000);  // expecting onMessage1
  }

  function onClose() {
    log("#0bf", "[RWS.onclose]")();
    disconnect('onclose');
  }

  function onError(e) {
    log('#a00', '[RWS.onerror]', e)();
  }

  function onMessage1(e) {
    clearTimers();
    timers.ping = setTimeout(ping, idlePingDelayMs);
    lastRecOrPingTime = Date.now();
    if (e.data === '["hi"]') {
      log('#0bf', '[RWS.onMessage1]', e.data)();
      ws.onmessage = onMessageN;
      opts.onConnect();
      sendBuffer();
    } else if (e.data === '["denied"]') {
      log("#a00", "[RWS.onMessage1]", e.data)();
      disconnect('denied');
    } else if (e.data.lastIndexOf('["bye"', 0) === 0) {
      log("#a00", "[RWS.onMessage1]", e.data)();
      disconnect('bye', null, 'polite');
    } else {  // shouldn't happen
      log("#a00", "[RWS.onMessage1] relaying")();
      opts.onMessage.call(self, e);
    }
  }

  function onMessageN(e) {
    clearTimers();
    timers.ping = setTimeout(ping, idlePingDelayMs);
    lastRecOrPingTime = Date.now();
    if (e.data === '["pong"]') {
      log('#0ac', '[RWS.pong]')();
      sendBuffer();
    } else if (e.data.lastIndexOf('["bye"', 0) === 0) {
      log("#0ac", "[RWS.onMessageN]", e.data)();
      disconnect('bye', null, 'polite');
    } else {
      opts.onMessage.call(self, e);
    }
  }

  function sendBuffer() {
    while (buffer.length) {
      var a = buffer.shift();
      log('#0bf', '[RWS] sending, buffered for %i ms: %s', Date.now() - a[1], (/\w+/.exec(a[0]) || a)[0])();
      ws.send(a[0]);
    }
  }

  function onOnlineConnect() {
    log("#0bf", "[RWS.onOnlineConnect]")();
    window.removeEventListener('online', onOnlineConnect);
    clearTimers();
    timers.connect = setTimeout(connect, 200);  // patience, Danielson
  }

  function ping() {
    log("#0bf", "[RWS.ping]")();
    ws.send('["ping"]');
    lastRecOrPingTime = Date.now();
    clearTimers();
    timers.disconnect = setTimeout(disconnect.bind(null, 'pong', 3), 3000);  // expecting onMessageN "pong"
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
}
