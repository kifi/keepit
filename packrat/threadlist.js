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
    insertOrReplace: function (nOld, nNew, log) {
      // remove old thread from old position
      var iOld = nOld ? this.ids.indexOf(nOld.thread) : -1;
      if (iOld >= 0) {
        this.ids.splice(iOld, 1);
        if (nOld.unread && !nOld.muted) {
          this.decNumUnreadUnmuted(log);
        }
      }

      // insert in chronological order
      for (var iNew = 0; iNew < this.ids.length; iNew++) {
        if (this.allById[this.ids[iNew]].time <= nNew.time) {
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
    insertOlder: function (olderThreadIds) {
      Array.prototype.push.apply(this.ids, olderThreadIds);
    },
    remove: function (threadId, log) {
      var nRemoved = 0, i;
      while (~(i = this.ids.indexOf(threadId))) {
        var n = this.ids.splice(i, 1)[0];
        if (this.numTotal > 0) {
          this.numTotal--;
        }
        if (n.unread && !n.muted) {
          this.decNumUnreadUnmuted(log);
        }
        nRemoved++;
      }
      return nRemoved;
    },
    includesAllSince: function (th) {
      return this.includesOldest || this.ids.length && th.time >= this.allById[this.ids[this.ids.length - 1]].time;
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
    // Counts the number of loaded threads that are unread and not muted
    countUnreadUnmuted: function () {
      for (var n = 0, i = 0; i < this.ids.length; i++) {
        var id = this.ids[i];
        var th = this.allById[id];
        if (th.unread && !th.muted) {
          n++;
        }
      }
      return n;
    },
    decNumUnreadUnmuted: function (log) {
      if (this.numUnreadUnmuted != null) {
        if (this.numUnreadUnmuted > 0) {
          this.numUnreadUnmuted--;
        } else {
          log('#a00', '[decNumUnreadUnmuted] already at:', this.numUnreadUnmuted);
          if (~experiments.indexOf('admin')) {
            this.numUnreadUnmuted--;
          }
        }
      }
    },
    incNumUnreadUnmuted: function () {
      if (this.numUnreadUnmuted != null) {
        this.numUnreadUnmuted++;
      }
    }
  };
}.call(this.exports || this));
