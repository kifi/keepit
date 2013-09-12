function ReconnectingWebSocket(opts) {
  const wordRe = /\w+/, minRetryConnectDelayMs = 300, maxRetryConnectDelayMs = 5000, idlePingDelayMs = 10000;
  var ws, self = this, buffer = [], pendingPong = false, pingBuffer = [], disTimeout, pingTimeout, lastRecOrPingTime, retryConnectDelayMs = minRetryConnectDelayMs;

  this.send = function(data) {
    if (pendingPong) {
      pingBuffer.push([data, +new Date]);
    } else if (ws && ws.greeted && ws.readyState == WebSocket.OPEN) {
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
    disconnect("close");
    this.send = this.close = buffer = null;
    window.removeEventListener("online", onOnlineConnect);
  };

  connect();

  function connect() {
    if (navigator.onLine) {
      api.log("#0bf", "[RWS.connect]");
      ws = new WebSocket(opts.url);
      ws.onopen = onOpen;
      ws.onclose = onClose;
      ws.onerror = onError;
      disTimeout = setTimeout(disconnect.bind(null, "connect", 20), 20000);  // expecting onOpen
    } else {
      api.log("#0bf", "[RWS.connect] offline");
      window.addEventListener("online", onOnlineConnect);
    }
  }

  function disconnect(why, timeoutSec) {
    var permanently = why === "close";
    api.log("#0bf", "[RWS.disconnect] why:", why, "readyState:", ws.readyState, "buffered:", buffer.length, "retry:", permanently ? "no" : retryConnectDelayMs);
    pendingPong = false;
    clearTimeout(disTimeout), disTimeout = null;
    clearTimeout(pingTimeout), pingTimeout = null;
    ws.onopen = ws.onmessage = ws.onerror = ws.onclose = null;
    ws.close();  // noop if already closed
    ws = null;
    if (!permanently) {
      setTimeout(connect, retryConnectDelayMs);
      retryConnectDelayMs = Math.min(maxRetryConnectDelayMs, retryConnectDelayMs * 1.5);
      opts.onDisconnect(why + (timeoutSec ? " " + timeoutSec + "s" : ""));
    }
  }

  function onOpen() {
    api.log("#0bf", "[RWS.onopen]");
    ws.onmessage = onMessage1;
    clearTimeout(disTimeout), disTimeout = setTimeout(disconnect.bind(null, "first message", 2), 2000);  // expecting onMessage1
  }

  function onClose() {
    api.log("#0bf", "[RWS.onclose]");
    disconnect("onClose");
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
      opts.onConnect();
      sendBuffer(buffer, "disconnect");
      pendingPong = false;
      sendBuffer(pingBuffer, "ping");
    } else if (e.data === '["denied"]') {
      api.log("#a00", "[RWS.onMessage1]", e.data);
      disconnect("onMessage1");
    } else {  // shouldn't happen
      api.log("#a00", "[RWS.onMessage1] relaying");
      opts.onMessage.call(self, e);
    }
  }

  function onMessageN(e) {
    clearTimeout(disTimeout), disTimeout = null;
    clearTimeout(pingTimeout), pingTimeout = setTimeout(ping, idlePingDelayMs);
    lastRecOrPingTime = +new Date;
    if (e.data === '["pong"]') {
      api.log("#0ac", "[RWS.pong]");
      pendingPong = false;
      sendBuffer(pingBuffer, "ping");
    } else {
      opts.onMessage.call(self, e);
    }
  }

  function sendBuffer(arr, name) {
    while (arr.length) {
      var a = arr.shift();
      api.log("#0bf", "[RWS] sending, %s buffered for %i ms: %s", name, new Date - a[1], (wordRe.exec(a[0]) || a)[0]);
      ws.send(a[0]);
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
    clearTimeout(disTimeout), disTimeout = setTimeout(disconnect.bind(null, "ping", 2), 2000);  // expecting onMessageN "pong"
    ws.send('["ping"]');
    pendingPong = true;
    lastRecOrPingTime = +new Date;
  }
}
