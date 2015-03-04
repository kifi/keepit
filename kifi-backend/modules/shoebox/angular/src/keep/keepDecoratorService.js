'use strict';

angular.module('kifi')

.factory('keepDecoratorService', ['tagService', 'util', 'profileService',
  function (tagService, util, profileService) {
    function processLibraries(item) {
      if (item.libraries && item.libraries.length > 0 && Array.isArray(item.libraries[0])) {
        var cleanedLibraries = [];
        var usersWithLibs = {};
        item.libraries.forEach( function (lib) {
          lib[0].keeper = lib[1];
          lib[0].keeperName = lib[1].firstName + ' ' + lib[1].lastName;
          if (lib[1].id !== profileService.me.id) {
            usersWithLibs[lib[1].id] = true;
            cleanedLibraries.push(lib[0]);
          }
        });

        item.keepers.forEach(function (keeper) {
          if (usersWithLibs[keeper.id]) {
            keeper.hidden = true;
          }
        });

        item.libraries = cleanedLibraries;
      }
    }


    function Keep (item, itemType) {
      if (!item) {
        return {};
      }

      processLibraries(item);

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
      this.showSocial = this.keepersTotal || (this.keepers && this.keepers.length > 0) || (this.libraries && this.libraries.length > 0);
    }

    // Add properties that are specific to a really kept Keep.
    Keep.prototype.buildKeep = function (keptItem, isMyBookmark) {
      this.id = keptItem.id;
      this.libraryId = keptItem.libraryId;
      this.isPrivate = keptItem.isPrivate;

      this.isMyBookmark = _.isBoolean(isMyBookmark) ? isMyBookmark : true;
      this.tagList = this.tagList || [];
      this.collections = this.collections || [];

      // deprecated: user addHashtag
      this.addTag = function (tag) {
        this.tagList.push(tag);
        this.collections.push(tag.id);
      };

      // deprecated: user removeHashtag
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

      this.hasHashtag = function (hashtag) {
        return _.contains(this.hashtags, hashtag);
      };

      this.addHashtag = function (hashtag) {
        this.addTag(hashtag); // to maintain backwards compatibility; eventually to be removed
        this.hashtags.push(hashtag);
      };

      this.removeHashtag = function (hashtag) {
        this.removeTag(hashtag); // to maintain backwards compatibility; eventually to be removed
        var idx = _.findIndex(this.hashtags, function (tag) {
          return tag === hashtag;
        });
        if (idx > -1) {
          this.hashtags.splice(idx, 1);
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
