'use strict';

angular.module('kifi')

.directive('kfKeepWhoText', [
  'profileService', 'libraryService',
  function (profileService, libraryService) {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'common/directives/keepWho/keepWhoText.tpl.html',
      scope: {
        keep: '=',
        deprecated: '='
      },
      link: function (scope) {
        var keep = scope.keep;

        scope.librariesEnabled = libraryService.isAllowed();

        // TODO(josh) remove this after we remove the deprecated keep card
        scope.useDeprecated = typeof scope.deprecated === 'boolean' && scope.deprecated;

        scope.me = profileService.me;

        scope.helprankEnabled = false;
        scope.$watch(function () {
          return profileService.me.seqNum;
        }, function () {
          scope.helprankEnabled = profileService.me && profileService.me.experiments && profileService.me.experiments.indexOf('helprank') > -1;
        });

        function librariesTotal() {
          return keep.librariesTotal || (keep.libraries ? keep.libraries.length : 0);
        }

        scope.hasKeepers = function () {
          return keep.keepers && (keep.keepers.length > 0);
        };

        scope.hasOthers = function () {
          return keep.keepersTotal > 0;
        };

        scope.hasLibraries = function () {
          return scope.librariesEnabled && librariesTotal() > 0;
        };

        scope.getFriendText = function () {
          var num = keep.keepers ? keep.keepers.length : 0;
          var text = (num === 1) ? '1 friend' : num + ' friends';
          if (scope.useDeprecated && keep.isMyBookmark) {
            return 'and ' + text;
          }
          return text;
        };

        scope.getOthersText = function () {
          var num = keep.keepersTotal ? keep.keepersTotal : 0;
          var text = (num === 1) ? '1 other' : num + ' others';
          if (scope.useDeprecated && (scope.hasKeepers() || keep.isMyBookmark)) {
            return 'and ' + text;
          }
          return text;
        };

        scope.getLibrariesText = function () {
          var num = librariesTotal();
          var text = (num === 1) ? '1 library' : num + ' libraries';
          return text;
        };
      }
    };
  }
])

;
