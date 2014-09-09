'use strict';

function ContactSearchCache(lifeSec) {
  this.cache = {};
  this.putAt = {};
  this.lifeSec = lifeSec;
}
ContactSearchCache.prototype = {
  key: function (o) {
    return (o.includeSelf ? '+' : '-') + o.q;
  },
  put: function (o, results) {
    this.prune();
    var key = this.key(o);
    this.cache[key] = results;
    this.putAt[key] = Date.now();
  },
  get: function (o) {
    var key = this.key(o);
    return this.cache[key];
  },
  prune: function () {
    var minPutAt = Date.now() - this.lifeSec;
    for (var key in this.cache) {
      if (this.putAt[key] < minPutAt) {
        delete this.cache[key];
        delete this.putAt[key];
      }
    }
  }
};

if (this.exports) {
  this.exports.ContactSearchCache = ContactSearchCache;
}
