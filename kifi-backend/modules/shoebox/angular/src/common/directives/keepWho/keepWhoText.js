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
        card: '='
      },
      link: function (scope) {

        scope.me = profileService.me;

        scope.helprankEnabled = false;
        scope.$watch(function () {
          return profileService.me.seqNum;
        }, function () {
          scope.helprankEnabled = profileService.me && profileService.me.experiments && profileService.me.experiments.indexOf('helprank') > -1;
        });

        scope.hasKeepers = function (card) {
          return card.keepers && (card.keepers.length > 0);
        };

        scope.hasOthers = function (card) {
          return card.others > 0;
        };

        scope.getFriendText = function (card) {
          var num = card.keepers ? card.keepers.length : 0;
          var text = (num === 1) ? '1 friend' : num + ' friends';
          return (!card.isMyBookmark) ? text : 'and ' + text;
        };

        scope.getOthersText = function (card) {
          var num = card.others ? card.others : 0;
          var text = (num === 1) ? '1 other' : num + ' others';
          return (card.isMyBookmark || card.keepers.length > 0) ? 'and ' + text : text;
        };
      }
    };
  }
]);
