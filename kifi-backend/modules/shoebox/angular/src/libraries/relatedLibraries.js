'use strict';

angular.module('kifi')

.directive('kfRelatedLibraries', [
  'libraryService', 'platformService', 'signupService',
  function (libraryService, platformService, signupService) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        parentLibrary: '&',
        relatedLibraries: '='
      },
      templateUrl: 'libraries/relatedLibraries.tpl.html',
      link: function (scope) {
        var parentLibrary = scope.parentLibrary();
        scope.join = function ($event) {
          $event.preventDefault();
          scope.$emit('trackLibraryEvent', 'click', { action: 'clickedLibraryRecJoinNow' });

          if (platformService.isSupportedMobilePlatform()) {
            platformService.goToAppOrStore();
          } else {
            signupService.register({ libraryId: parentLibrary.id });
          }
        };

        scope.clickSeeMore = function () {
          scope.$emit('trackLibraryEvent', 'click', { action: 'clickedLibraryRecSeeMore' });
        };
      }
    };
  }
]);
