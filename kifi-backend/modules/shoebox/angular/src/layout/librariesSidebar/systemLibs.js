'use strict';

angular.module('kifi')

.directive('kfSystemLibs', [
  '$location', 'util', 'libraryService', 'profileService', '$rootScope',
  function ($location, util, libraryService, profileService, $rootScope) {
    return {
      templateUrl: 'layout/librariesSidebar/systemLibs.tpl.html',
      link: function (scope) {
        scope.librariesEnabled = false;
        scope.libraries = [];
        scope.mainLib = {};
        scope.secretLib = {};

        scope.$watch(function () {
          return libraryService.isAllowed();
        }, function (n) {
          scope.librariesEnabled = n || false;
          if (scope.librariesEnabled) {
            libraryService.fetchLibrarySummaries().then(function () {
              scope.libraries = libraryService.librarySummaries;
              scope.mainLib = _.find(scope.libraries, function (lib) {
                  return lib.kind === 'system_main';
              });
              scope.secretLib = _.find(scope.libraries, function (lib) {
                  return lib.kind === 'system_secret';
              });
            });
          }
        });

        scope.addLibrary = function () {
          $rootScope.$emit('showGlobalModal', 'manageLibrary');
        };

        scope.isActive = function (path) {
          var loc = $location.path();
          return loc === path || util.startsWith(loc, path + '/');
        };

        scope.inRecoExperiment = function () {
          return profileService.me && profileService.me.experiments && profileService.me.experiments.indexOf('recos_beta') >= 0;
        };
      }
    };
  }
]);
