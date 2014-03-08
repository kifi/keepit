function SocketCommander (socket, handlers, onConnect, onDisconnect, log) {
  this.socket = socket;
  this._seq = 0;
  this._nextCallbackId = 1;
  this._callbacks = {};  // TODO: garbage collect old uncalled callbacks?
  this._handlers = handlers;
  this._onConnect = onConnect;
  this._onDisconnect = onDisconnect;
  // this._timeouts = {};
  // this._outstanding = {};
  this._log = log;
}
SocketCommander.prototype = {
  send: function (arr, callback) {
    if (this.socket) {
      if (callback) {
        var id = this._nextCallbackId++;
        arr.splice(1, 0, id);
        this._callbacks[id] = {
          //req: arr,
          sent: Date.now(),
          func: callback
        };
      }
      this._log('#0ac', '[socket.send]', arr)();
      this.socket.send(JSON.stringify(arr));
    } else {
      this._log('#c00', '[socket.send] ignored, closed', arr)();
    }
  },
  close: function () {
    if (this.socket) {
      this.socket.close();
      this.socket = null;
    } else {
      this._log('#c00', '[socket.close] already closed')();
    }
  },
  onConnect: function () {
    this._onConnect();
  },
  onDisconnect: function (why, sec) {
    this._onDisconnect(why, sec);
  },
  onMessage: function (data) {
    var msg = JSON.parse(data);
    if (Array.isArray(msg)) {
      var id = msg.shift();
      if (id > 0) {
        var cb = this._callbacks[id];
        if (cb) {
          this._log('#0ac', '[socket.receive] response', id, '(' + (Date.now() - cb.sent) + 'ms)')();
          delete this._callbacks[id];
          cb.func.apply(null, msg);
        } else {
          this._log('#0ac', '[socket.receive] ignoring', id, msg)();
        }
      } else {
        var handler = this._handlers[id];
        if (handler) {
          this._log('#0ac', '[socket.receive]', id)();
          handler.apply(null, msg);
        } else {
          this._log('#0ac', '[socket.receive] ignoring', id, msg)();
        }
      }
    } else {
      this._log('#0ac', '[socket.receive] ignoring', msg)();
    }
  }
};

if (this.exports) {
  this.exports.SocketCommander = SocketCommander;
}
