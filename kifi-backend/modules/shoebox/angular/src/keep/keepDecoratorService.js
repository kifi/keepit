'use strict';

angular.module('kifi')

.factory('keepDecoratorService', [
  'util', 'profileService',
  function (util, profileService) {

    function Keep(item, itemType) {
      if (!item) {
        return {};
      }

      item.libraries = _(item.libraries).filter(function (lib) {
        return lib[1].id !== profileService.me.id;
      }).map(function (lib) {
        lib[0].keeper = lib[1];
        return lib[0];
      }).value();

      this.keeps = []; // default value in case `item` doesn't have it
      _.assign(this, item);
      this.itemType = itemType;

      // For recommendations, the id field in the recommended page coming from the backend is
      // actually the url id of the page. To disambiguate from a page's keep id,
      // use 'urlId' as the property name for the url id.
      // TODO: update backend to pass 'urlId' instead of 'id' in the JSON object.
      // This really shouldn't be happening on the client side.
      if (this.itemType === 'reco') {
        this.urlId = this.id;
        delete this.id;
      }

      // Helper functions.
      function shouldShowSmallImage(summary) {
        var imageWidthThreshold = 200;
        return (summary.imageWidth && summary.imageWidth < imageWidthThreshold) || summary.description;
      }

      function hasSaneAspectRatio(summary) {
        var aspectRatio = summary.imageWidth && summary.imageHeight && summary.imageWidth / summary.imageHeight;
        var saneAspectRatio = aspectRatio > 0.5 && aspectRatio < 3;
        var bigEnough = summary.imageWidth + summary.imageHeight > 200;
        return bigEnough && saneAspectRatio;
      }

      function getKeepReadTime(summary) {
        var read_times = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 20, 30, 60];

        var wc = summary && summary.wordCount;
        if (wc < 0) {
          return null;
        } else {
          var minutesEstimate = wc / 250;
          var found = _.find(read_times, function (t) { return minutesEstimate < t; });
          return found ? found + ' min' : '> 1 h';
        }
      }

      // Add new properties to the keep.
      this.titleAttr = this.title || this.url;
      this.titleHtml = this.title || (this.summary && this.summary.title) || util.formatTitleFromUrl(this.url);
      if (this.summary && hasSaneAspectRatio(this.summary)) {
        this[shouldShowSmallImage(this.summary) ? 'hasSmallImage' : 'hasBigImage'] = true;
      }
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
