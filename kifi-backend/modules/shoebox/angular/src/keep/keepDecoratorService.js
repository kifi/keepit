'use strict';

angular.module('kifi')

.factory('keepDecoratorService', [
  'profileService',
  function (profileService) {

    function Keep(item) {
      if (item.libraries && item.libraries.length && item.libraries[0].length) {
        item.libraries = _(item.libraries).filter(function (lib) {
          return lib[1].id !== profileService.me.id;
        }).map(function (lib) {
          lib[0].keeper = lib[1];
          return lib[0];
        }).value()
      };

      this.keeps = []; // in case `item` doesn't have it
      _.assign(this, item);
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
