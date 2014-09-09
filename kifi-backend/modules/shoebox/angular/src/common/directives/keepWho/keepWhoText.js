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

        scope.me = profileService.me;

        scope.helprankEnabled = false;
        scope.$watch(function () {
          return profileService.me.seqNum;
        }, function () {
          scope.helprankEnabled = profileService.me && profileService.me.experiments && profileService.me.experiments.indexOf('helprank') > -1;
        });

        scope.hasKeepers = function (keep) {
          return keep.keepers && (keep.keepers.length > 0);
        };

        scope.hasOthers = function (keep) {
          return keep.others > 0;
        };

        scope.getFriendText = function (keep) {
          var num = keep.keepers ? keep.keepers.length : 0;
          var text = (num === 1) ? '1 friend' : num + ' friends';
          return (!keep.isMyBookmark) ? text : 'and ' + text;
        };

        scope.getOthersText = function (keep) {
          var num = keep.others ? keep.others : 0;
          var text = (num === 1) ? '1 other' : num + ' others';
          return (keep.isMyBookmark || keep.keepers.length > 0) ? 'and ' + text : text;
        };
      }
    };
  }
]);
