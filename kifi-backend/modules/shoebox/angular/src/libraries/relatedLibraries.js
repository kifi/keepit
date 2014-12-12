'use strict';

angular.module('kifi')

.directive('kfRelatedLibraries', [
  function () {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        relatedLibraries: '='
      },
      templateUrl: 'libraries/relatedLibraries.tpl.html',
      link: function (/*scope, element, attrs*/) {
      }
    };
  }
])

.directive('kfRelatedLibraryCard', [
  function () {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        library: '='
      },
      templateUrl: 'libraries/relatedLibraryCard.tpl.html',
      link: function (scope/*, element, attrs*/) {
        scope.clickLibraryReco = function ($event) {
          $event.target.href = scope.library.libraryUrl + '?o=lr';
          scope.$emit('trackLibraryEvent', 'click', { action: 'clickedLibraryRec' });
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
