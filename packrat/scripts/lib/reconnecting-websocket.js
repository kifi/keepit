function ReconnectingWebSocket(url, onmessage) {
  var ws, self = this, buffer = [];

  connect();

  this.send = function(data) {
    if (ws && ws.readyState == WebSocket.OPEN) {
      ws.send(data);
    } else {
      buffer.push([data, +new Date]);
      if (!ws) {
        connect();
      }
    }
  };
  this.close = function() {
    buffer.length = 0;
    if (ws) {
      ws.close();
    }
    self.send = self.close = function() {};
  };

  function connect() {
    console.debug("[ReconnectingWebSocket.connect]");

    ws = new WebSocket(url);
    var t = setTimeout(onConnectTimeout.bind(null, ws), 5000);

    ws.onopen = function() {
      clearTimeout(t);
      while (buffer.length) {
        var a = buffer.shift();
        console.debug("[ReconnectingWebSocket.onopen] sending, buffered for", new Date - a[1], "ms:", a[0]);
        ws.send(a[0]);
      }
    };

    ws.onclose = function() {
      console.debug("[ReconnectingWebSocket.onclose] buffer size:", buffer.length);
      clearTimeout(t);
      ws = null;
      if (buffer.length) {
        connect();
      }
    };

    ws.onmessage = onmessage.bind(self);
  }

  function onConnectTimeout(ws) {
    console.debug("[ReconnectingWebSocket.onConnectTimeout]");
    ws.close();
  }
}
