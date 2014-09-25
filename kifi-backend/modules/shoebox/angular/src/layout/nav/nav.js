'use strict';

angular.module('kifi')

.directive('kfNav', [
  '$location', 'util', 'friendService', 'tagService', 'profileService', 'libraryService', '$rootScope', '$anchorScroll',
  function ($location, util, friendService, tagService, profileService, libraryService, $rootScope, $anchorScroll) {
    return {
      //replace: true,
      restrict: 'A',
      templateUrl: 'layout/nav/nav.tpl.html',
      link: function (scope /*, element, attrs*/ ) {
        scope.counts = {
          friendsCount: friendService.totalFriends(),
          friendsNotifCount: friendService.requests.length
        };

        scope.librariesEnabled = false;
        scope.mainLib = {};
        scope.secretLib = {};
        scope.userLibs = [];
        scope.invitedLibs = [];

        scope.$watch(function () {
          return libraryService.isAllowed();
        }, function (newVal) {
          scope.librariesEnabled = newVal;
          if (scope.librariesEnabled) {
            libraryService.fetchLibrarySummaries().then(function () {
              var libraries = libraryService.librarySummaries;
              scope.mainLib = _.find(libraries, function (lib) {
                  return lib.kind === 'system_main';
              });
              scope.secretLib = _.find(libraries, function (lib) {
                  return lib.kind === 'system_secret';
              });
              scope.userLibs = _.filter(libraries, function (lib) {
                return lib.kind === 'user_created';
              });
              scope.invitedLibs = libraryService.invitedSummaries;

              var lines;
              for (var i = 0; i < scope.userLibs.length; i++) {
                lines = shortenName(scope.userLibs[i].name);
                scope.userLibs[i].firstLine = lines[0];
                scope.userLibs[i].secondLine = lines[1];
              }
              for (i = 0; i < scope.invitedLibs.length; i++) {
                lines = shortenName(scope.invitedLibs[i].name);
                scope.invitedLibs[i].firstLine = lines[0];
                scope.invitedLibs[i].secondLine = lines[1];
              }
            });
          }
        });

        scope.addLibrary = function () {
          $rootScope.$emit('showGlobalModal', 'manageLibrary');
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

        var maxLength = 22;
        function shortenName (fullName) {
          var firstLine = fullName;
          var secondLine = '';
          if (fullName.length > maxLength) {
            var full = false;
            var line = '';
            while (!full) {
              var pos = fullName.indexOf(' ');
              if (line.length + pos < maxLength) {
                line = line + fullName.substr(0, pos+1);
                fullName = fullName.slice(pos+1);
              } else {
                full = true;
              }
            }
            firstLine = line;
            var remainingLen = fullName.length;
            if (remainingLen > 0) {
              if (remainingLen < maxLength) {
                secondLine = fullName.substr(0, remainingLen);
              } else {
                secondLine = fullName.substr(0, maxLength-3) + '...';
              }
            }
          }
          return [firstLine, secondLine];
        }

        // Filter Box Stuff
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
          //libraryService.filterList(scope.filter.name);
        };

        // Scroll-Bar Stuff
        scope.scrollAround = function() {
          $anchorScroll();
        };
      }
    };
  }
]);
