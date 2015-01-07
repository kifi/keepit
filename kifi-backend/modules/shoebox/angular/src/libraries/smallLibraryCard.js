'use strict';

angular.module('kifi')

.directive('kfSmallLibraryCard', [
  function () {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        library: '=',
        origin: '@',
        action: '@'
      },
      templateUrl: 'libraries/smallLibraryCard.tpl.html',
      link: function (scope/*, element, attrs*/) {
        scope.clickCard = function ($event) {
          $event.target.href = scope.library.libraryPath + '?o=' + scope.origin;
          scope.$emit('trackLibraryEvent', 'click', { action: scope.action });
        };

        if (scope.library.followers.length > 0 && scope.library.otherFollowersCount > 0) {
          scope.followersToShow = _.take(scope.library.followers, 3);
        } else {
          scope.followersToShow = scope.library.followers;
        }

        scope.otherFollowersCount = scope.library.numFollowers - scope.followersToShow.length;
      }
    };
  }
])

;
