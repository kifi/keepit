var sockets = {};
self.port.on("open_socket", openSocket);
self.port.on("close_socket", closeSocket);
if (self.options) {
  openSocket(self.options.socketId, self.options.url);
}

function openSocket(socketId, url) {
  console.log("[worker:openSocket]", socketId, url);
  var socket = sockets[socketId] = new ReconnectingWebSocket(url);
  socket.onmessage = function(data) {
    self.port.emit("socket_message", socketId, data.data);
  };
}

function closeSocket(socketId) {
  console.log("[worker:closeSocket]", socketId);
  var socket = sockets[socketId];
  if (socket) {
    socket.close();
    delete sockets[socketId];
  }
}
