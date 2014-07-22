/**
 * Simple WebSocket server that sends a 'reload' message to all connected
 * clients every time the module's exported function is called.
 */

var ws = require('ws');

var sockets = [];
var WebSocketServer = ws.Server;
var wss = new WebSocketServer({port: 35729});
console.log('livereload server started');

wss.on('connection', function(ws) {
  sockets.push(ws);
  console.log('client connected');

  function removeClient() {
    console.log('client disconnected');
    var index = sockets.indexOf(ws);
    if (index > -1) {
      sockets.splice(index, 1);
    }
  }

  ws.on('close', removeClient);
  ws.on('error', removeClient);
});

module.exports = function () {
  if (sockets.length > 0) {
    sockets.forEach(function (ws) {
      ws.send('reload');
    })
  } else {
    console.log('reloading, but no one listening.');
  }
}