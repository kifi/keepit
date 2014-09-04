'use strict';

angular.module('kifi')

.factory('keepDecoratorService', ['util',
  function (util) {
    function Keep (rawKeep, keepType) {
      if (!rawKeep) {
        return {};
      }

      _.assign(this, rawKeep);

      // For recommendations, the id field in the recommended page coming from the backend is 
      // actually the url id of the page. To disambiguate from a page's keep id,
      // use 'urlId' as the property name for the url id.
      // TODO: update backend to pass 'urlId' instead of 'id' in the JSON object.
      if (keepType === 'reco') {
        this.keepType = keepType;
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
      this.titleHtml = this.title || util.formatTitleFromUrl(this.url);
      this.hasSmallImage = this.summary && shouldShowSmallImage(this.summary) && hasSaneAspectRatio(this.summary);
      this.hasBigImage = this.summary && (!shouldShowSmallImage(this.summary) && hasSaneAspectRatio(this.summary));
      this.readTime = getKeepReadTime(this.summary);
      this.showSocial = this.others || (this.keepers && this.keepers.length > 0);
    }

    // Add properties that are specific to a really kept Keep.
    Keep.prototype.buildKeep = function (keep, isMyBookmark) {
      this.id = keep.id;
      this.isPrivate = keep.isPrivate;

      this.isMyBookmark = isMyBookmark;
      if (typeof this.isMyBookmark !== 'boolean') {
        this.isMyBookmark = true;
      }

      this.tagList = this.tagList || [];
      this.collections = this.collections || [];

      // Todo: move addTag and removeTag to some kind of mixin so they don't hang
      // onto every really kept Keep.
      this.addTag = function (tag) {
        this.tagList.push(tag);
        this.collections.push(tag.id);
      };

      this.removeTag = function (tagId) {
        var idx1 = _.findIndex(this.tagList, function (tag) {
          return tag.id === tagId;
        });
        if (idx1 > -1) {
          this.tagList.splice(idx1, 1);
        }
        var idx2 = this.collections.indexOf(tagId);
        if (idx2 > -1) {
          this.collections.splice(idx2, 1);
          return true;
        } else {
          return false;
        }
      };
    };

    var api = {
      Keep: Keep
    };

    return api;
  }
]);