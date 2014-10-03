'use strict';

angular.module('kifi')

.directive('kfNav', [
  '$location', '$window', '$rootScope', '$timeout', 'util', 'friendService', 'modalService', 'tagService', 'profileService', 'libraryService', '$interval',
  function ($location, $window, $rootScope, $timeout, util, friendService, modalService, tagService, profileService, libraryService, $interval) {
    return {
      //replace: true,
      restrict: 'A',
      templateUrl: 'layout/nav/nav.tpl.html',
      link: function (scope , element /*, attrs*/) {
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

        var w = angular.element($window);
        var scrollableLibList = element.find('.kf-scrollable-libs');

        // on resizing window -> trigger new turn -> reset library list height
        w.bind('resize', function () {
          scope.$apply(function () {
            setLibListHeight();
          });
        });

        function setLibListHeight() {
          if (scrollableLibList.offset()) {
            scrollableLibList.height(w.height() - (scrollableLibList.offset().top - w[0].pageYOffset));
          }
          if (scope.refreshScroll) {
            scope.refreshScroll();
          }
        }

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

        // we thought about putting this check into the watch function above,
        // but even when libraries are enabled, the element is found but the offset is 0
        // in setLibListHeight(), if the offset is 0, the height of scrollableLibList == window height
        // and thus no scrolly-bar =[
        // once the offset is not 0, we know it's in the correct position and we can cancel this interval
        var lastHeight = 0;
        var promiseLibList = $interval(function() {
          scrollableLibList = element.find('.kf-scrollable-libs');
          if (scrollableLibList.offset() && scrollableLibList.offset().top > 0) {
            setLibListHeight();
            if (lastHeight === scrollableLibList.height()) {
              $interval.cancel(promiseLibList);
            }
            lastHeight = scrollableLibList.height(); // probably a better way to do this - sometimes scrollbar is buggy but this secures the height
          }
        }, 100);

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
          var sorting, sortByOptTime, libs, invited;
          switch (sortBy) {
            case 'A-Z':
              sorting = function(a,b) {
                if (a.name > b.name) { return 1; } 
                else if (a.name < b.name) { return -1; } 
                else { return 0; }
              };
              libs = scope.allUserLibs.sort(sorting);
              invited = scope.allInvitedLibs.sort(sorting);
              break;

            case 'Z-A':
              sorting = function(a,b) {
                if (a.name < b.name) { return 1; } 
                else if (a.name > b.name) { return -1; } 
                else { return 0; }
              };
              libs = scope.allUserLibs.sort(sorting);
              invited = scope.allInvitedLibs.sort(sorting);
              break;

            case 'NumKeeps':
              sorting = function(a,b) {
                if (a.numKeeps < b.numKeeps) { return 1; } 
                else if (a.numKeeps > b.numKeeps) { return -1; } 
                else { return 0; }
              };
              libs = scope.allUserLibs.sort(sorting);
              invited = scope.allInvitedLibs.sort(sorting);
              break;

            case 'NumFollowers':
              sorting = function(a,b) {
                if (a.numFollowers < b.numFollowers) { return 1; } 
                else if (a.numFollowers > b.numFollowers) { return -1; } 
                else { return 0; }
              };
              libs = scope.allUserLibs.sort(sorting);
              invited = scope.allInvitedLibs.sort(sorting);
              break;

            case 'LastViewed':
              sorting = function(a,b) { // assumes lastViewed is not undefined
                if (a.lastViewed < b.lastViewed) { return 1; } 
                else if (a.lastViewed > b.lastViewed) { return -1; } 
                else { return 0; }
              };
              sortByOptTime = function(libs) {
                // first partitions lastViewed fields that are undefined & adds them after sorted list
                var partition = _.values(
                                _.groupBy(libs, function(lib) { 
                                  return lib.lastViewed === undefined;
                                })
                              );
                var libsUndefinedTimes = partition[0];
                var libsRealTimes = partition[1];

                var order = libsRealTimes.sort(sorting);
                return order.concat(libsUndefinedTimes);
              };
              libs = sortByOptTime(scope.allUserLibs);
              invited = sortByOptTime(scope.allInvitedLibs);
              break;

            case 'LastKept':
              sorting = function(a,b) { // assumes lastKept is not undefined
                if (a.lastKept < b.lastKept) { return 1; } 
                else if (a.lastKept > b.lastKept) { return -1; } 
                else { return 0; }
              };
              sortByOptTime = function(libs) {
                // first partitions lastKept fields that are undefined & adds them after sorted list
                var partition = _.values(
                                _.groupBy(libs, function(lib) { 
                                  return lib.lastKept === undefined;
                                })
                              );
                var libsUndefinedTimes = partition[0];
                var libsRealTimes = partition[1];

                var order = libsRealTimes.sort(sorting);
                return order.concat(libsUndefinedTimes);
              };
              libs = sortByOptTime(scope.allUserLibs);
              invited = sortByOptTime(scope.allInvitedLibs);
              break;
            default:
              break;
          }
          util.replaceArrayInPlace(scope.userLibsToShow, libs);
          util.replaceArrayInPlace(scope.invitedLibsToShow, invited);
        };
      }
    };
  }
]);
