function log() {
  'use strict';
  var d = new Date, ds = d.toString(), a = Array.slice(arguments);
  for (var i = 0; i < a.length; i++) {
    var v = a[i];
    if (typeof v == "object") {
      try {
        a[i] = JSON.stringify(v);
      } catch (e) {
        a[i] = String(v) + "{" + Object.keys(v).join(",") + "}";
      }
    }
  }
  a.unshift("'" + ds.substr(0,2) + ds.substr(15,9) + "." + String(+d).substr(10) + "'");
  console.log.apply(console, a);
}

var sockets = {};
self.port.on("open_socket", openSocket);
self.port.on("close_socket", closeSocket);
self.port.on("socket_send", socketSend);
if (self.options) {
  openSocket(self.options.socketId, self.options.url);
}

function openSocket(socketId, url) {
  log("[worker:openSocket]", socketId, url);
  sockets[socketId] = new ReconnectingWebSocket(url, {
    onConnect: function() {
      self.port.emit("socket_connect", socketId);
    },
    onDisconnect: function(why) {
      self.port.emit("socket_disconnect", socketId, why);
    },
    onMessage: function(e) {
      self.port.emit("socket_message", socketId, e.data);
    }});
}

function closeSocket(socketId) {
  log("[worker:closeSocket]", socketId);
  var socket = sockets[socketId];
  if (socket) {
    socket.close();
    delete sockets[socketId];
  }
}

function socketSend(socketId, data) {
  var socket = sockets[socketId];
  if (socket) {
    log("[worker:socketSend]", socketId, data);
    socket.send(data);
  } else {
    console.error("[worker:socketSend] no socket", socketId, data);
  }
}
