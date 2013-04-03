function ReconnectingWebSocket(url, onmessage) {
  var ws, self = this, buffer = [];

  this.send = function(data) {
    if (ws) {
      ws.send(data);
    } else {
      buffer.push(data);
      connect();
    }
  };

  connect();

  function connect() {
    console.debug("[ReconnectingWebSocket.connect]");

    ws = new WebSocket(url);
    var t = setTimeout(timeout.bind(null, ws), 5000);

    ws.onopen = function(event) {
      console.debug("[ReconnectingWebSocket.onopen] buffer size:", buffer.length);
      clearTimeout(t);
      while (buffer.length) {
        this.send(buffer.shift());
      }
    };

    ws.onclose = function(event) {
      console.debug("[ReconnectingWebSocket.onclose] buffer size:", buffer.length);
      clearTimeout(t);
      ws = null;
      if (buffer.length) {
        connect();
      }
    };

    ws.onmessage = onmessage.bind(self);
  }

  function timeout(ws) {
    console.debug("[ReconnectingWebSocket.timeout]");
    ws.close();
  }
}
