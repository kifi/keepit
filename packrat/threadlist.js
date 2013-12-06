(function () {
  'use strict';

  this.ThreadList = ThreadList;

  // allById is a read-only object for looking up threads by their IDs
  // recentThreadIds should be contiguous and in chronological order, newest first
  // numUnreadUnmnuted may refer to threads not yet represented in this ThreadList
  function ThreadList(allById, recentThreadIds, numUnreadUnmuted, lastSeenTimeStr) {
    this.allById = allById;
    this.ids = recentThreadIds;
    this.numUnreadUnmuted = numUnreadUnmuted || 0;
    this.lastSeen = lastSeenTimeStr ? new Date(lastSeenTimeStr) : null;
  };
  ThreadList.prototype = {
    contains: function (threadId) {
      return this.ids.indexOf(threadId) >= 0;
    },
    updateLastSeen: function(timeStr) {
      var time = new Date(timeStr);
      if (!this.lastSeen || this.lastSeen < time) {
        this.lastSeen = time;
        return true;
      }
    },
    insertAll: function(arr) {
      arr.forEach(this.insert.bind(this));
    },
    insert: function (n) {
      // remove old representation of same thread first
      var i = this.ids.indexOf(n.thread);
      var nOld = i >= 0 ? this.ids.splice(i, 1)[0] : null;
      if (nOld && nOld.unread && !nOld.muted) {
        this.numUnreadUnmuted--;
      }

      // add in chronological order
      var time = new Date(n.time);
      for (i = 0; i < this.ids.length; i++) {
        if (new Date(this.allById[this.ids[i]].time) <= time) {
          break;
        }
      }
      this.ids.splice(i, 0, n.thread);
      if (n.unread && !n.muted) {
        this.numUnreadUnmuted++;
      }
    },
    insertOlder: function(olderThreadIds) {
      Array.prototype.push.apply(this.ids, olderThreadIds);
    },
    anyUnread: function() {
      return this.numUnreadUnmuted > 0 || this.ids.some(function (id) {
        return this.allById[id].unread;
      }, this);
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
    decNumUnreadUnmuted: function() {
      if (this.numUnreadUnmuted <= 0) {
        log('#a00', '[decNumUnreadUnmuted] already at:', this.numUnreadUnmuted)();
      }
      if (this.numUnreadUnmuted > 0 || ~session.experiments.indexOf('admin')) {  // exposing -1 to admins to help debug
        this.numUnreadUnmuted--;
      }
    }
  };
}.call(this.exports || this));
