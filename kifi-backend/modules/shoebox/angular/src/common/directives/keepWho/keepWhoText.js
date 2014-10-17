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

        scope.hasKeepers = function () {
          return keep.keepers && (keep.keepers.length > 0);
        };

        scope.hasOthers = function () {
          return keep.others > 0;
        };

        scope.hasLibraries = function () {
          return scope.librariesEnabled && 1 > 0; // TODO(josh) how to get the libraries a keep is in?
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
          var num = keep.others ? keep.others : 0;
          var text = (num === 1) ? '1 other' : num + ' others';
          if (scope.useDeprecated && (scope.hasKeepers() || keep.isMyBookmark)) {
            return 'and ' + text;
          }
          return text;
        };

        scope.getLibrariesText = function () {
          var num = keep.librariesTotal;
          var text = (num === 1) ? '1 library' : num + ' libraries';
          return text;
        };
      }
    };
  }
])

;
