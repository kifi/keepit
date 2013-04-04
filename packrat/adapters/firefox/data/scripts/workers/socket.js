var api = {log: function() {
  var d = new Date, ds = d.toString(), a = Array.slice(arguments);
  for (var i = 0; i < a.length; i++) {
    var v = a[i];
    if (typeof v == "object") {
      a[i] = JSON.stringify(v);
    }
  }
  console.log("'" + ds.substr(0,2) + ds.substr(15,9) + "." + String(+d).substr(10) + "'", a.join(" "));
}};

var sockets = {};
self.port.on("open_socket", openSocket);
self.port.on("close_socket", closeSocket);
self.port.on("socket_send", socketSend);
if (self.options) {
  openSocket(self.options.socketId, self.options.url);
}

function openSocket(socketId, url) {
  api.log("[worker:openSocket]", socketId, url);
  sockets[socketId] = new ReconnectingWebSocket(url, function(e) {
    self.port.emit("socket_message", socketId, e.data);
  });
}

function closeSocket(socketId) {
  api.log("[worker:closeSocket]", socketId);
  var socket = sockets[socketId];
  if (socket) {
    socket.close();
    delete sockets[socketId];
  }
}

function socketSend(socketId, data) {
  var socket = sockets[socketId];
  if (socket) {
    api.log("[worker:socketSend]", socketId, data);
    socket.send(JSON.stringify(data));
  } else {
    console.error("[worker:socketSend] no socket", socketId, data);
  }
}
