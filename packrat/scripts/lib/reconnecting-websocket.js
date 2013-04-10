function ReconnectingWebSocket(url, onmessage) {
  const wordRe = /\w+/, minRetryConnectDelayMs = 300, maxRetryConnectDelayMs = 5000;
  var ws, self = this, buffer = [], closed, retryConnectDelayMs = minRetryConnectDelayMs;

  connect();

  this.send = function(data) {
    if (closed) {
      throw "closed, try back tomorrow";
    } else if (ws && ws.readyState == WebSocket.OPEN) {
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
    var t = setTimeout(onConnectTimeout.bind(null, ws), 5000);

    ws.onopen = function() {
      clearTimeout(t);
      while (buffer.length) {
        var a = buffer.shift();
        api.log("#0bf", "[RWS.onopen] sending, buffered for %i ms: %s", new Date - a[1], (wordRe.exec(a[0]) || a)[0]);
        ws.send(a[0]);
      }
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

    ws.onmessage = onmessage.bind(self);
  }

  function onConnectTimeout(ws) {
    api.log("#0bf", "[RWS.onConnectTimeout]");
    ws.onerror = function() {};
    ws.close();
  }
}
