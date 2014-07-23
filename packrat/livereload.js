/**
 * Simple WebSocket client that refreshes the extension
 * every time it receives a 'reload' message.
 * (see server in gulp/livereload.js)
 */

(function() {
  var socket = new WebSocket("ws://dev.ezkeep.com:35729");
  socket.onmessage = function (event) {
    if (event.data === 'reload') {
      window.chrome.runtime.reload();
    }
  }
})();
