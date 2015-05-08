'use strict';

angular.module('kifi')

.factory('keepDecoratorService', [
  'profileService',
  function (profileService) {

    function Keep(item, itemType) {
      item.libraries = _(item.libraries).filter(function (lib) {
        return lib[1].id !== profileService.me.id;
      }).map(function (lib) {
        lib[0].keeper = lib[1];
        return lib[0];
      }).value();

      this.keeps = []; // default value in case `item` doesn't have it
      _.assign(this, item);
      this.itemType = itemType;

      function getKeepReadTime(summary) {
        var wc = summary && summary.wordCount;
        if (wc < 0) {
          return null;
        } else {
          var minutesEstimate = wc / 250;
          var n = _.find([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 20, 30, 60], function (t) { return minutesEstimate < t; });
          return n ? n + ' min' : '1h';
        }
      }

      // Add new properties to the keep.
      this.readTime = getKeepReadTime(this.summary);
    }

    // Add properties that are specific to a really kept Keep.
    Keep.prototype.buildKeep = function (keptItem, isMyBookmark) {
      this.id = keptItem.id;
      this.libraryId = keptItem.libraryId;
      this.isMyBookmark = _.isBoolean(isMyBookmark) ? isMyBookmark : true;
    };

    Keep.prototype.makeUnkept = function () {
      this.unkept = true;
      this.isMyBookmark = false;
    };

    Keep.prototype.makeKept = function () {
      this.unkept = false;
      this.isMyBookmark = true;
    };

    return {Keep: Keep};
  }
]);
