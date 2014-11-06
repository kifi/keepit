'use strict';

angular.module('kifi')

.directive('kfKeepWhoText', [
  'profileService',
  function (profileService) {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'common/directives/keepWho/keepWhoText.tpl.html',
      scope: {
        keep: '='
      },
      link: function (scope) {
        var keep = scope.keep;

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
          return keep.others > 0;
        };

        scope.hasLibraries = function () {
          return librariesTotal() > 0;
        };

        scope.getFriendText = function () {
          var num = keep.keepers ? keep.keepers.length : 0;
          var text = (num === 1) ? '1 friend' : num + ' friends';
          return text;
        };

        scope.getOthersText = function () {
          var num = keep.others ? keep.others : 0;
          var text = (num === 1) ? '1 other' : num + ' others';
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
