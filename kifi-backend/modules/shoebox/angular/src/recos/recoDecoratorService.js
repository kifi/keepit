'use strict';

angular.module('kifi')

.factory('recoDecoratorService', ['keepDecoratorService', 'util', 'friendService', 'userService',
  function (keepDecoratorService, util, friendService, userService) {

    function Recommendation(rawReco, type) {
      this.recoData = {
        type: type,  // This is either 'recommended' or 'popular'.
        kind: rawReco.kind,  // This is either 'keep' or 'library'.
        reasons: rawReco.metaData || [],
        explain: rawReco.explain
      };

      this.recoData.reasons.forEach(function (reason) {
        if (!reason.name && reason.url) {
          reason.name = util.formatTitleFromUrl(reason.url);
        }
      });

      if (this.recoData.kind === 'keep') {
        rawReco.itemInfo.libraries = rawReco.itemInfo.libraries;
        var libUsers = {};
        rawReco.itemInfo.libraries.forEach( function (lib) {
          lib.keeperPic = friendService.getPictureUrlForUser(lib.owner);
          lib.keeperProfileUrl = userService.getProfileUrl(lib.owner.username);
          libUsers[lib.owner.id] = true;
        });
        //don't show a face only if there is also a library for that person
        rawReco.itemInfo.keepers.forEach(function (keeper) {
          if (libUsers[keeper.id]) {
            keeper.hidden = true;
          }
        });
        this.recoKeep = new keepDecoratorService.Keep(rawReco.itemInfo, 'reco');
      } else {
        this.recoLib = rawReco.itemInfo;

        // All recommended libraries are published user-created libraries.
        this.recoLib.kind = 'user_created';
        this.recoLib.visibility = 'published';
      }
    }

    var api = {
      newUserRecommendation: function (rawReco) {
        return new Recommendation(rawReco, 'recommended');
      },

      newPopularRecommendation: function (rawReco) {
        return new Recommendation(rawReco, 'popular');
      }
    };

    return api;
  }
]);
