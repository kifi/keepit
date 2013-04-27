function ReconnectingWebSocket(url, onMessage, onConnect) {
  const wordRe = /\w+/, minRetryConnectDelayMs = 300, maxRetryConnectDelayMs = 5000, idlePingDelayMs = 30000;
  var ws, self = this, buffer = [], closed, t, retryConnectDelayMs = minRetryConnectDelayMs;

  connect();

  this.send = function(data) {
    if (closed) {
      throw "closed, try back tomorrow";
    } else if (ws && ws.greeted && ws.readyState == WebSocket.OPEN) {
      ws.send(data);
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
      }
    }
  };

  function connect() {
    api.log("#0bf", "[RWS.connect]");

    ws = new WebSocket(url);
    t = setTimeout(onConnectTimeout.bind(null, ws), 25000);

    ws.onopen = function() {
      api.log("#0bf", "[RWS.onopen]");
      clearTimeout(t);
      t = setTimeout(onConnectTimeout.bind(null, ws), 2000);
    };
    ws.onclose = function(e) {
      api.log("#0bf", "[RWS.onclose] %o buffer: %o", e, buffer);
      clearTimeout(t);
      ws = null;
      if (!closed) {
        api.log("#0bf", "[RWS.onclose] will reconnect in %i ms", retryConnectDelayMs);
        t = setTimeout(connect, retryConnectDelayMs);
        retryConnectDelayMs = Math.min(maxRetryConnectDelayMs, retryConnectDelayMs * 1.5);
      }
    };
    ws.onerror = onError;
    ws.onmessage = onMessage1;
  }

  function onError(e) {
    api.log("#a00", "[RWS.onerror]", e);
  }

  function onMessage1(e) {
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
    clearTimeout(t);
    t = setTimeout(ping, idlePingDelayMs);
  }

  function onMessageN(e) {
    if (e.data === '["pong"]') {
      api.log("#0ac", "[RWS.pong]");
    } else {
      onMessage.call(self, e);
    }
    clearTimeout(t);
    t = setTimeout(ping, idlePingDelayMs);
  }

  function onConnectTimeout(ws) {
    api.log("#0bf", "[RWS.onConnectTimeout]", ws.readyState);
    ws.close();
  }

  function ping() {
    api.log("#0bf", "[RWS.ping]");
    self.send('["ping"]');
    t = setTimeout(onConnectTimeout.bind(null, ws), 2000);
  }
}
