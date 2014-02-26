function ReconnectingWebSocket(opts) {
  'use strict';
  var wordRe = /\w+/, minRetryConnectDelayMs = 300, maxRetryConnectDelayMs = 5000, idlePingDelayMs = 10000;
  var ws, self = this, buffer = [], disTimeout, pingTimeout, lastRecOrPingTime, retryConnectDelayMs = minRetryConnectDelayMs;

  this.send = function(data) {
    if (ws && ws.readyState === WebSocket.OPEN && !disTimeout) {
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
    disconnect("close");
    this.send = this.close = buffer = null;
    window.removeEventListener("online", onOnlineConnect);
  };

  connect();

  function connect() {
    if (navigator.onLine) {
      log("#0bf", "[RWS.connect]")();
      ws = new WebSocket(opts.url);
      ws.onopen = onOpen;
      ws.onclose = onClose;
      ws.onerror = onError;
      disTimeout = setTimeout(disconnect.bind(null, "connect", 20), 20000);  // expecting onOpen
    } else {
      log("#0bf", "[RWS.connect] offline")();
      window.addEventListener("online", onOnlineConnect);
    }
  }

  function disconnect(why, timeoutSec) {
    var permanently = why === "close";
    log("#0bf", "[RWS.disconnect] why:", why, "readyState:", ws && ws.readyState, "buffered:", buffer.length, "retry:", permanently ? "no" : retryConnectDelayMs)();
    clearTimeout(disTimeout), disTimeout = null;
    clearTimeout(pingTimeout), pingTimeout = null;
    if (ws) {
      ws.onopen = ws.onmessage = ws.onerror = ws.onclose = null;
      ws.close();  // noop if already closed
      ws = null;
    }
    if (!permanently) {
      setTimeout(connect, retryConnectDelayMs);
      retryConnectDelayMs = Math.min(maxRetryConnectDelayMs, retryConnectDelayMs * 1.5);
      opts.onDisconnect(why + (timeoutSec ? " " + timeoutSec + "s" : ""));
    }
  }

  function onOpen() {
    log("#0bf", "[RWS.onopen]")();
    ws.onmessage = onMessage1;
    clearTimeout(disTimeout), disTimeout = setTimeout(disconnect.bind(null, "first message", 2), 2000);  // expecting onMessage1
  }

  function onClose() {
    log("#0bf", "[RWS.onclose]")();
    disconnect("onClose");
  }

  function onError(e) {
    log("#a00", "[RWS.onerror]", e)();
  }

  function onMessage1(e) {
    clearTimeout(disTimeout), disTimeout = null;
    pingTimeout = setTimeout(ping, idlePingDelayMs);
    lastRecOrPingTime = Date.now();
    if (e.data === '["hi"]') {
      log("#0bf", "[RWS.onMessage1]", e.data)();
      ws.onmessage = onMessageN;
      retryConnectDelayMs = minRetryConnectDelayMs;
      opts.onConnect();
      sendBuffer();
    } else if (e.data === '["denied"]') {
      log("#a00", "[RWS.onMessage1]", e.data)();
      disconnect("onMessage1");
    } else {  // shouldn't happen
      log("#a00", "[RWS.onMessage1] relaying")();
      opts.onMessage.call(self, e);
    }
  }

  function onMessageN(e) {
    clearTimeout(disTimeout), disTimeout = null;
    clearTimeout(pingTimeout), pingTimeout = setTimeout(ping, idlePingDelayMs);
    lastRecOrPingTime = Date.now();
    if (e.data === '["pong"]') {
      log("#0ac", "[RWS.pong]")();
      sendBuffer();
    } else {
      opts.onMessage.call(self, e);
    }
  }

  function sendBuffer() {
    while (buffer.length) {
      var a = buffer.shift();
      log("#0bf", "[RWS] sending, buffered for %i ms: %s", Date.now() - a[1], (wordRe.exec(a[0]) || a)[0])();
      ws.send(a[0]);
    }
  }

  function onOnlineConnect() {
    log("#0bf", "[RWS.onOnlineConnect]")();
    window.removeEventListener("online", onOnlineConnect);
    setTimeout(connect, 200);  // patience, Danielson
  }

  function ping() {
    log("#0bf", "[RWS.ping]")();
    clearTimeout(pingTimeout), pingTimeout = null;
    clearTimeout(disTimeout), disTimeout = setTimeout(disconnect.bind(null, "ping", 2), 3000);  // expecting onMessageN "pong"
    ws.send('["ping"]');
    lastRecOrPingTime = Date.now();
  }
}
