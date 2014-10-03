'use strict';

angular.module('kifi')

.directive('kfNav', [
  '$location', '$rootScope', 'util', 'friendService', 'modalService', 'tagService', 'profileService', 'libraryService', '$anchorScroll',
  function ($location, $rootScope, util, friendService, modalService, tagService, profileService, libraryService, $anchorScroll) {
    return {
      //replace: true,
      restrict: 'A',
      templateUrl: 'layout/nav/nav.tpl.html',
      link: function (scope /*, element, attrs*/) {
        scope.counts = {
          friendsCount: friendService.totalFriends(),
          friendsNotifCount: friendService.requests.length
        };

        scope.librariesEnabled = false;
        scope.librarySummaries = libraryService.librarySummaries;
        
        scope.mainLib = {};
        scope.secretLib = {};

        scope.allUserLibs = [];
        scope.allInvitedLibs = libraryService.invitedSummaries;
        scope.userLibsToShow = [];
        scope.invitedLibsToShow = [];

        var fuseOptions = {
           keys: ['name'],
           threshold: 0.3 // 0 means exact match, 1 means match with anything
        };
        var librarySummarySearch = {};
        var invitedSummarySearch = {};

        scope.$watch(function () {
          return libraryService.isAllowed();
        }, function (newVal) {
          scope.librariesEnabled = newVal;
          if (scope.librariesEnabled) {
            libraryService.fetchLibrarySummaries().then(function () {
              scope.mainLib = _.find(scope.librarySummaries, { 'kind' : 'system_main' });
              scope.secretLib = _.find(scope.librarySummaries, { 'kind' : 'system_secret' });
              scope.allUserLibs = _.filter(scope.librarySummaries, { 'kind' : 'user_created' });
              
              util.replaceArrayInPlace(scope.userLibsToShow, scope.allUserLibs);
              util.replaceArrayInPlace(scope.invitedLibsToShow, scope.allInvitedLibs);
            });
          }
        });

        $rootScope.$on('changedLibrary', function () {
          if (scope.librariesEnabled) {
            scope.allUserLibs = _.filter(scope.librarySummaries, { 'kind' : 'user_created' });
            util.replaceArrayInPlace(scope.userLibsToShow, scope.allUserLibs);
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
          librarySummarySearch = new Fuse(scope.allUserLibs, fuseOptions);
          invitedSummarySearch = new Fuse(scope.allInvitedLibs, fuseOptions);
          var term = scope.filter.name;
          var newMyLibs = scope.allUserLibs;
          var newMyInvited = scope.allInvitedLibs;
          if (term.length) {
            newMyLibs = librarySummarySearch.search(term);
            newMyInvited = invitedSummarySearch.search(term);
          }

          util.replaceArrayInPlace(scope.userLibsToShow, newMyLibs);
          util.replaceArrayInPlace(scope.invitedLibsToShow, newMyInvited);

          return scope.userLibsToShow.concat(scope.invitedLibsToShow);
        };

        scope.sortLibraries = function (sortBy) {
          var sorting;
          switch (sortBy) {
            case 'A-Z':
              sorting = function(a,b) {
                if (a.name > b.name) { return 1; } 
                else if (a.name < b.name) { return -1; } 
                else { return 0; }
              };
              break;
            case 'Z-A':
              sorting = function(a,b) {
                if (a.name < b.name) { return 1; } 
                else if (a.name > b.name) { return -1; } 
                else { return 0; }
              };
              break;
            case 'NumKeeps':
              sorting = function(a,b) {
                if (a.numKeeps < b.numKeeps) { return 1; } 
                else if (a.numKeeps > b.numKeeps) { return -1; } 
                else { return 0; }
              };
              break;
            case 'NumFollowers':
              sorting = function(a,b) {
                if (a.numFollowers < b.numFollowers) { return 1; } 
                else if (a.numFollowers > b.numFollowers) { return -1; } 
                else { return 0; }
              };
              break;
            case 'LastViewed':
              break;
            case 'LastKept':
              break;
            default:
              break;
          }
          var libs = scope.allUserLibs.sort(sorting);
          var invited = scope.allInvitedLibs.sort(sorting);
          util.replaceArrayInPlace(scope.userLibsToShow, libs);
          util.replaceArrayInPlace(scope.invitedLibsToShow, invited);
        };

        // Scroll-Bar Stuff
        scope.scrollAround = function() {
          $anchorScroll();
        };
      }
    };
  }
]);
