'use strict';

angular.module('kifi')

.directive('kfUserLibs', [
  'libraryService',
  function (libraryService) {
    return {
      templateUrl: 'layout/librariesSidebar/userLibs.tpl.html',
      link: function (scope) {
        scope.librariesEnabled = false;
        scope.userLibraries = [];
        scope.invited = [];

        scope.$watch(function () {
          return libraryService.isAllowed();
        }, function (n) {
          scope.librariesEnabled = n || false;
          if (scope.librariesEnabled) {
            libraryService.fetchLibrarySummaries().then(function () {
              scope.userLibraries = _.filter(libraryService.librarySummaries, function (lib) {
                return lib.kind === 'user_created';
              });
              scope.invited = libraryService.invitedSummaries;
            });
          }
        });

      }
    };
  }
]);
