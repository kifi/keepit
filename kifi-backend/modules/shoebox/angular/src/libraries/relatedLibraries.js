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
      link: function (scope/*, element, attrs*/) {
        var parentLibrary = scope.parentLibrary();
        scope.join = function ($event) {
          $event.preventDefault();

          libraryService.trackEvent('visitor_clicked_page', parentLibrary, {
            type: 'libraryLanding',
            action: 'clickedCreatedYourOwnJoinButton'
          });

          if (platformService.isSupportedMobilePlatform()) {
            platformService.goToAppOrStore();
          } else {
            signupService.register({ libraryId: parentLibrary.id });
          }
        };
      }
    };
  }
])

;
