var sockets = {};
self.port.on("open_socket", openSocket);
self.port.on("close_socket", closeSocket);
self.port.on("socket_send", socketSend);
if (self.options) {
  openSocket(self.options.socketId, self.options.url);
}

function openSocket(socketId, url) {
  console.log("[worker:openSocket]", socketId, url);
  (sockets[socketId] = new ReconnectingWebSocket(url)).onmessage = function(data) {
    self.port.emit("socket_message", socketId, data.data);
  };
}

function closeSocket(socketId) {
  console.log("[worker:closeSocket]", socketId);
  var socket = sockets[socketId];
  if (socket) {
    socket.close();
    socket.onmessage = function() {};
    delete sockets[socketId];
  }
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
