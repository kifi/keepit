'use strict';

angular.module('kifi')

.directive('kfRelatedLibraries', [
  '$stateParams', 'libraryService', 'platformService', 'signupService',
  function ($stateParams, libraryService, platformService, signupService) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        heading: '@',
        libraryId: '@',
        relatedLibraries: '='
      },
      templateUrl: 'libraries/relatedLibraries.tpl.html',
      link: function (scope) {
        scope.join = function ($event) {
          $event.preventDefault();
          scope.$emit('trackLibraryEvent', 'click', { action: 'clickedLibraryRecJoinNow' });

          if (platformService.isSupportedMobilePlatform()) {
            platformService.goToAppOrStore();
          } else {
            signupService.register({libraryId: scope.libraryId, libAuthToken: $stateParams.authToken, intent: 'follow' });
          }
        };

        scope.clickSeeMore = function () {
          scope.$emit('trackLibraryEvent', 'click', { action: 'clickedLibraryRecSeeMore' });
        };
      }
    };
  }
]);
