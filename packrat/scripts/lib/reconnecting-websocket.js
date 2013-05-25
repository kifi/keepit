function ReconnectingWebSocket(url, onMessage, onConnect) {
  const wordRe = /\w+/, minRetryConnectDelayMs = 300, maxRetryConnectDelayMs = 5000, idlePingDelayMs = 30000;
  var ws, self = this, buffer = [], closed, t, lastRecOrPingTime, retryConnectDelayMs = minRetryConnectDelayMs;

  connect();

  this.send = function(data) {
    if (closed) {
      throw "closed, try back tomorrow";
    } else if (ws && ws.greeted && ws.readyState == WebSocket.OPEN) {
      ws.send(data);
      if (new Date - lastRecOrPingTime > idlePingDelayMs) {
        ping();
      }
    } else {
      buffer.push([data, +new Date]);
    }
  };
  this.close = function() {
    if (!closed) {
      closed = true;
      buffer = null;
      if (ws) {
        ws.close();
        t = setTimeout(onClose.bind(ws), 1000);  // in case browser does not fire close event right promptly
      }
      window.removeEventListener("online", onOnline);
      window.removeEventListener("offline", onOffline);
    }
  };

  window.addEventListener("online", onOnline);
  window.addEventListener("offline", onOffline); // fires in Chrome when WiFi conn lost or changed

  function connect() {
    api.log("#0bf", "[RWS.connect]");
    ws = new WebSocket(url);
    ws.onopen = onOpen;
    ws.onclose = onClose;
    ws.onerror = onError;
    ws.onmessage = onMessage1;
    t = setTimeout(onConnectTimeout.bind(null, ws), 25000);
  }

  function onOpen() {
    api.log("#0bf", "[RWS.onopen]");
    clearTimeout(t);
    t = setTimeout(onConnectTimeout.bind(null, ws), 2000);
  }

  function onClose(e) {
    if (this !== ws) return;
    api.log("#0bf", "[RWS.onclose] %o buffer: %o", e, buffer);
    clearTimeout(t);
    ws = null;
    if (!closed) {
      api.log("#0bf", "[RWS.onclose] will reconnect in %i ms", retryConnectDelayMs);
      t = setTimeout(connect, retryConnectDelayMs);
      retryConnectDelayMs = Math.min(maxRetryConnectDelayMs, retryConnectDelayMs * 1.5);
    }
  }

  function onError(e) {
    api.log("#a00", "[RWS.onerror]", e);
  }

  function onMessage1(e) {
    clearTimeout(t);
    t = setTimeout(ping, idlePingDelayMs);
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
    } else {
      api.log("#a00", "[RWS.onMessage1] relaying");
      onMessage.call(self, e);
    }
  }

  function onMessageN(e) {
    clearTimeout(t);
    t = setTimeout(ping, idlePingDelayMs);
    lastRecOrPingTime = +new Date;
    if (e.data === '["pong"]') {
      api.log("#0ac", "[RWS.pong]");
    } else {
      onMessage.call(self, e);
    }
  }

  function onConnectTimeout(ws) {
    api.log("#0bf", "[RWS.onConnectTimeout] readyState:", ws && ws.readyState);
    clearTimeout(t);
    if (ws) {
      ws.close();
      t = setTimeout(onClose.bind(ws), 1000);  // browser might not fire close event for 60+ sec
    }
  }

  function onOnline() {
    api.log("#0bf", "[RWS.onOnline] readyState:", ws && ws.readyState);
    if (!ws && !closed) {
      clearTimeout(t);
      connect();
    }
  }

  function onOffline() {
    api.log("#0bf", "[RWS.onOffline] readyState:", ws && ws.readyState);
    clearTimeout(t);
    if (ws) {
      ws.close();
      t = setTimeout(onClose.bind(ws), 1000);
    }
  }

  function ping() {
    if (ws && !closed) {
      api.log("#0bf", "[RWS.ping]");
      self.send('["ping"]');
      clearTimeout(t);
      t = setTimeout(onConnectTimeout.bind(null, ws), 2000);
      lastRecOrPingTime = +new Date;
    }
  }
}
