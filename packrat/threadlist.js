(function () {
  'use strict';

  this.ThreadList = ThreadList;

  // allById is a read-only object for looking up threads by their IDs
  // recentThreadIds should be contiguous and in chronological order, newest first
  // numUnreadUnmuted and numTotal may refer to threads not yet inserted into this ThreadList
  function ThreadList(allById, recentThreadIds, numTotal, numUnreadUnmuted) {
    this.allById = allById;
    this.ids = recentThreadIds;
    this.numTotal = numTotal;
    this.numUnreadUnmuted = numUnreadUnmuted;
  };
  ThreadList.prototype = {
    contains: function (threadId) {
      return this.ids.indexOf(threadId) >= 0;
    },
    // Returns whether the new thread was new to this list (true if nNew was just inserted, false if nNew replaced nOld).
    insertOrReplace: function (nOld, nNew) {
      // remove old thread from old position
      var iOld = nOld ? this.ids.indexOf(nOld.thread) : -1;
      if (iOld >= 0) {
        this.ids.splice(iOld, 1);
        if (nOld.unread && !nOld.muted) {
          this.decNumUnreadUnmuted();
        }
      }

      // insert in chronological order
      var time = new Date(nNew.time);
      for (var iNew = 0; iNew < this.ids.length; iNew++) {
        if (new Date(this.allById[this.ids[iNew]].time) <= time) {
          break;
        }
      }
      this.ids.splice(iNew, 0, nNew.thread);
      if (this.numTotal != null && iOld < 0) {
        this.numTotal++;
      }
      if (this.numUnreadUnmuted != null && nNew.unread && !nNew.muted) {
        this.numUnreadUnmuted++;
      }
      return iOld < 0;
    },
    insertOlder: function(olderThreadIds) {
      Array.prototype.push.apply(this.ids, olderThreadIds);
    },
    remove: function(threadId) {
      var nRemoved = 0, i;
      while (~(i = this.ids.indexOf(threadId))) {
        var n = this.ids.splice(i, 1)[0];
        if (this.numTotal > 0) {
          this.numTotal--;
        }
        if (n.unread && !n.muted) {
          this.decNumUnreadUnmuted();
        }
        nRemoved++;
      }
      return nRemoved;
    },
    forEachUnread: function (f) {
      for (var i = 0; i < this.ids.length; i++) {
        var id = this.ids[i];
        if (this.allById[id].unread) {
          f(id);
        }
      }
    },
    firstUnreadUnmuted: function () {
      for (var i = 0; i < this.ids.length; i++) {
        var id = this.ids[i];
        var th = this.allById[id];
        if (th.unread && !th.muted) {
          return id;
        }
      }
    },
    decNumTotal: function() {
      if (this.numTotal != null) {
        dec.call(this, 'numTotal');
      }
    },
    decNumUnreadUnmuted: function() {
      if (this.numUnreadUnmuted != null) {
        dec.call(this, 'numUnreadUnmuted');
      }
    }
  };

  function dec(field) {
    if (this[field] > 0) {
      this[field]--;
    } else {
      log('#a00', '[dec:' + field + '] already at:', this[field])();
      if (~session.experiments.indexOf('admin')) {
        this[field]--;
      }
    }
  }
}.call(this.exports || this));
