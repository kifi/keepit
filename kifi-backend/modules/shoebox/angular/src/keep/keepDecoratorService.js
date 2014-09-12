'use strict';

angular.module('kifi')

.factory('keepDecoratorService', ['tagService', 'util',
  function (tagService, util) {
    function Keep (item, itemType) {
      if (!item) {
        return {};
      }

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
      this.titleHtml = this.title || util.formatTitleFromUrl(this.url);
      if (this.summary && hasSaneAspectRatio(this.summary)) {
        this[shouldShowSmallImage(this.summary) ? 'hasSmallImage' : 'hasBigImage'] = true;
      }
      this.readTime = getKeepReadTime(this.summary);
      this.showSocial = this.others || (this.keepers && this.keepers.length > 0);
    }

    // Add properties that are specific to a really kept Keep.
    Keep.prototype.buildKeep = function (keptItem, isMyBookmark) {
      this.id = keptItem.id;
      this.isPrivate = keptItem.isPrivate;
      
      this.isMyBookmark = _.isBoolean(isMyBookmark) ? isMyBookmark : true;
      this.tagList = this.tagList || [];
      this.collections = this.collections || [];

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

    Keep.prototype.makeUnkept = function () {
      this.unkept = true;
      this.isMyBookmark = false;

      if (this.tagList){
        this.tagList.forEach(function (tag) {
          var existingTag = tagService.getById(tag.id);

          if (existingTag) {
            existingTag.keeps--;
          }
        });
      }
    };

    Keep.prototype.makeKept = function () {
      this.unkept = false;
      this.isMyBookmark = true;
      if (this.tagList) {
        this.tagList.forEach(function (tag) {
          var existingTag = tagService.getById(tag.id);
          if (existingTag) {
            existingTag.keeps++;
          }
        });
      }
    };

    // angular.toJSON does not copy over the Keep prototype methods.
    // This function reconstitutes a Keep object with all the prototype methods.
    function reconstituteKeepFromJson(keep) {
      return _.extend(new Keep(keep), {isMyBookmark: keep.isMyBookmark});
    }

    var api = {
      Keep: Keep,
      reconstituteKeepFromJson: reconstituteKeepFromJson
    };

    return api;
  }
]);
