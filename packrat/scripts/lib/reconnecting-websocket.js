function ReconnectingWebSocket(url, onMessage, onConnect) {
  const wordRe = /\w+/, minRetryConnectDelayMs = 300, maxRetryConnectDelayMs = 5000, idlePingDelayMs = 30000;
  var ws, self = this, buffer = [], disTimeout, pingTimeout, lastRecOrPingTime, retryConnectDelayMs = minRetryConnectDelayMs;

  this.send = function(data) {
    if (ws && ws.greeted && ws.readyState == WebSocket.OPEN) {
      ws.send(data);
      if (new Date - lastRecOrPingTime > idlePingDelayMs) {  // a way to recover from timeout failure/unreliability
        ping();
      }
    } else {
      buffer.push([data, +new Date]);
    }
  };
  this.close = function() {
    api.log("#0bf", "[RWS.close]");
    disconnect(true);
    this.send = this.close = buffer = null;
    window.removeEventListener("online", onOnlineConnect);
  };

  connect();

  function connect() {
    if (navigator.onLine) {
      api.log("#0bf", "[RWS.connect]");
      ws = new WebSocket(url);
      ws.onopen = onOpen;
      ws.onclose = onClose;
      ws.onerror = onError;
      disTimeout = setTimeout(disconnect, 20000);  // expecting onOpen
    } else {
      api.log("#0bf", "[RWS.connect] offline");
      window.addEventListener("online", onOnlineConnect);
    }
  }

  function disconnect(permanently) {
    api.log("#0bf", "[RWS.disconnect] readyState:", ws.readyState, "buffered:", buffer.length, "retry:", !permanently && retryConnectDelayMs);
    clearTimeout(disTimeout), disTimeout = null;
    clearTimeout(pingTimeout), pingTimeout = null;
    ws.onopen = ws.onmessage = ws.onerror = ws.onclose = null;
    ws.close();  // noop if already closed
    ws = null;
    if (!permanently) {
      setTimeout(connect, retryConnectDelayMs);
      retryConnectDelayMs = Math.min(maxRetryConnectDelayMs, retryConnectDelayMs * 1.5);
    }
  }

  function onOpen() {
    api.log("#0bf", "[RWS.onopen]");
    ws.onmessage = onMessage1;
    clearTimeout(disTimeout), disTimeout = setTimeout(disconnect, 2000);  // expecting onMessage1
  }

  function onClose() {
    api.log("#0bf", "[RWS.onclose]");
    disconnect();
  }

  function onError(e) {
    api.log("#a00", "[RWS.onerror]", e);
  }

  function onMessage1(e) {
    clearTimeout(disTimeout), disTimeout = null;
    pingTimeout = setTimeout(ping, idlePingDelayMs);
    lastRecOrPingTime = +new Date;
    if (e.data === '["hi"]') {
      api.log("#0bf", "[RWS.onMessage1]", e.data);
      ws.greeted = true;
      ws.onmessage = onMessageN;
      retryConnectDelayMs = minRetryConnectDelayMs;
      onConnect();
      while (buffer.length) {
        var a = buffer.shift();
        api.log("#0bf", "[RWS] sending, buffered for %i ms: %s", new Date - a[1], (wordRe.exec(a[0]) || a)[0]);
        ws.send(a[0]);
      }
    } else if (e.data === '["denied"]') {
      api.log("#a00", "[RWS.onMessage1]", e.data);
      disconnect();
    } else {  // shouldn't happen
      api.log("#a00", "[RWS.onMessage1] relaying");
      onMessage.call(self, e);
    }
  }

  function onMessageN(e) {
    clearTimeout(disTimeout), disTimeout = null;
    clearTimeout(pingTimeout), pingTimeout = setTimeout(ping, idlePingDelayMs);
    lastRecOrPingTime = +new Date;
    if (e.data === '["pong"]') {
      api.log("#0ac", "[RWS.pong]");
    } else {
      onMessage.call(self, e);
    }
  }

  function onOnlineConnect() {
    api.log("#0bf", "[RWS.onOnlineConnect]");
    window.removeEventListener("online", onOnlineConnect);
    setTimeout(connect, 200);  // patience, Danielson
  }

  function ping() {
    api.log("#0bf", "[RWS.ping]");
    clearTimeout(pingTimeout), pingTimeout = null;
    clearTimeout(disTimeout), disTimeout = setTimeout(disconnect, 2000);  // expecting onMessageN "pong"
    ws.send('["ping"]');
    lastRecOrPingTime = +new Date;
  }
}
