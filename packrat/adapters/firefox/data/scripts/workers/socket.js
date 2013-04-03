var sockets = {};
self.port.on("open_socket", openSocket);
self.port.on("socket_send", socketSend);
if (self.options) {
  openSocket(self.options.socketId, self.options.url);
}

function openSocket(socketId, url) {
  console.log("[worker:openSocket]", socketId, url);
  sockets[socketId] = new ReconnectingWebSocket(url, function(e) {
    self.port.emit("socket_message", socketId, e.data);
  });
}

function socketSend(socketId, data) {
  var socket = sockets[socketId];
  if (socket) {
    console.log("[worker:socketSend]", socketId, data);
    socket.send(JSON.stringify(data));
  } else {
    console.error("[worker:socketSend] no socket", socketId, data);
  }
}
