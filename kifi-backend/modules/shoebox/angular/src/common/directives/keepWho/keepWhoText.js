'use strict';

angular.module('kifi.keepWhoText', ['kifi.profileService'])

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
        profileService.getMe();

        scope.helprankEnabled = function() {
          var experiments = scope.me.experiments;
          return (experiments.indexOf('helprank') > -1);
        };

        scope.isPrivate = function () {
          return scope.keep.isPrivate || false;
        };

        scope.hasKeepers = function () {
          var keep = scope.keep;
          return !!(keep.keepers && keep.keepers.length);
        };

        scope.hasOthers = function () {
          var keep = scope.keep;
          return keep.others > 0;
        };

        scope.hasClicks = function() {
          var keep = scope.keep;
          return (keep.clickCount && keep.clickCount > 0);
        };

        scope.getClicks = function() {
          var keep = scope.keep;
          return (keep.clickCount || 0);
        };

        scope.getFriendText = function () {
          var keepers = scope.keep.keepers,
            len = keepers && keepers.length || 0,
            text;
          if (len === 1) {
            text = '1 friend';
          }
          text = len + ' friends';
          if (!scope.keep.isMyBookmark) {
            return text;
          }
          return '+ ' + text;
        };

        scope.getOthersText = function () {
          var others = scope.keep.others || 0;
          var text;
          if (others === 1) {
            text = '1 other';
          } else {
            text = others + ' others';
          }
          if (scope.keep.isMyBookmark || scope.keep.keepers.length > 0) {
            text = '+ ' + text;
          }
          return text;
        };

        scope.getClicksText = function() {
          var clickCount = scope.keep.clickCount || 0;
          var text;
          if (clickCount === 0) {
            text = '';
          } else {
            text = clickCount + ' discovered this keep!';
          }
          return text;
        };

        scope.isOnlyMine = function () {
          return !scope.hasKeepers() && !scope.keep.others;
        };
      }
    };
  }
]);
