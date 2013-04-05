function ReconnectingWebSocket(url, onmessage) {
  var ws, self = this, buffer = [], closed;
  const wordRe = /\w+/;

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
        connect();
      }
    };

    ws.onmessage = onmessage.bind(self);
  }

  function onConnectTimeout(ws) {
    api.log("#0bf", "[RWS.onConnectTimeout]");
    ws.close();
  }
}
