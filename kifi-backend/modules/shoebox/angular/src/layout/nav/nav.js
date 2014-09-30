'use strict';

angular.module('kifi')

.directive('kfNav', [
  '$location', '$rootScope', 'util', 'friendService', 'modalService', 'tagService', 'profileService', 'libraryService', '$anchorScroll',
  function ($location, $rootScope, util, friendService, modalService, tagService, profileService, libraryService, $anchorScroll) {
    return {
      //replace: true,
      restrict: 'A',
      templateUrl: 'layout/nav/nav.tpl.html',
      link: function (scope) {
        scope.counts = {
          friendsCount: friendService.totalFriends(),
          friendsNotifCount: friendService.requests.length
        };

        scope.librariesEnabled = false;
        scope.mainLib = libraryService.mainLib;
        scope.secretLib = libraryService.secretLib;
        scope.userLibs = libraryService.userLibsToShow;
        scope.invitedLibs = libraryService.invitedLibsToShow;

        scope.$watch(function () {
          return libraryService.isAllowed();
        }, function (newVal) {
          scope.librariesEnabled = newVal;
          if (scope.librariesEnabled) {
            libraryService.fetchLibrarySummaries();
          }
        });

        $rootScope.$on('changedLibrary', function () {
          if (scope.librariesEnabled) {
            scope.userLibs = _.filter(libraryService.librarySummaries, function (lib) {
              return lib.kind === 'user_created';
            });
          }
        });

        scope.addLibrary = function () {
          modalService.open({
            template: 'libraries/manageLibraryModal.tpl.html'
          });
        };

        scope.$watch(function () {
          return friendService.requests.length;
        }, function (value) {
          scope.counts.friendsNotifCount = value;
        });

        scope.$watch(friendService.totalFriends, function (value) {
          scope.counts.friendsCount = value;
        });

        scope.$watch(tagService.getTotalKeepCount, function (val) {
          scope.counts.keepCount = val;
        });

        scope.isActive = function (path) {
          var loc = $location.path();
          return loc === path || util.startsWith(loc, path + '/');
        };

        scope.inRecoExperiment = function () {
          return profileService.me && profileService.me.experiments && profileService.me.experiments.indexOf('recos_beta') >= 0;
        };

        // Filter Box Stuff
        scope.orders = {
          options: ['A-Z','Z-A','# Keeps'],
          currentOrder: ''
        };

        scope.sortLibsBy = function () {
          switch (scope.orders.currentOrder) {
            case 'A-Z':
              libraryService.sortLibrariesByName(1);
              break;
            case 'Z-A':
              libraryService.sortLibrariesByName(-1);
              break;
            case '# Keeps':
              libraryService.sortLibrariesByNumKeeps();
              break;
            default:
              break;
          }
        };

        scope.filter = {};
        scope.isFilterFocused = false;
        var preventClearFilter = false;
        scope.filter.name = '';
        scope.focusFilter = function () {
          scope.isFilterFocused = true;
        };

        scope.disableClearFilter = function () {
          preventClearFilter = true;
        };

        scope.enableClearFilter = function () {
          preventClearFilter = false;
        };

        scope.blurFilter = function () {
          scope.isFilterFocused = false;
          if (!preventClearFilter) {
            scope.clearFilter();
          }
        };

        scope.clearFilter = function () {
          scope.filter.name = '';
          scope.onFilterChange();
        };

        /*
        function getFilterValue() {
          return scope.filter && scope.filter.name || '';
        }*/

        scope.onFilterChange = function () {
          libraryService.filterLibraries(scope.filter.name);
        };

        // Scroll-Bar Stuff
        scope.scrollAround = function() {
          $anchorScroll();
        };
      }
    };
  }
]);
