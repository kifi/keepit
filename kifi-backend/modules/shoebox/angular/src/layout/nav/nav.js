'use strict';

angular.module('kifi')

.directive('kfNav', [
  '$location', '$window', '$rootScope', '$timeout', '$document', 'util',
    'friendService', 'modalService', 'tagService', 'profileService', 'libraryService', '$interval',
  function ($location, $window, $rootScope, $timeout, $document, util,
    friendService, modalService, tagService, profileService, libraryService, $interval) {
    return {
      //replace: true,
      restrict: 'A',
      templateUrl: 'layout/nav/nav.tpl.html',
      link: function (scope , element /*, attrs*/) {
        //
        // Internal data.
        //
        var allUserLibs = [];

        var w = angular.element($window);
        var scrollableLibList = element.find('.kf-scrollable-libs');
        var dropDownMenu = element.find('.kf-sort-libs-button');


        //
        // Scope data.
        //
        scope.librariesEnabled = false;
        scope.mainLib = {};
        scope.secretLib = {};
        scope.userLibsToShow = [];
        scope.invitedLibsToShow = [];

        scope.counts = {
          friendsCount: friendService.totalFriends(),
          friendsNotifCount: friendService.requests.length
        };


        //
        // Internal methods.
        //
        function setLibListHeight() {
          if (scrollableLibList.offset()) {
            scrollableLibList.height(w.height() - (scrollableLibList.offset().top - w[0].pageYOffset));
          }
          if (scope.refreshScroll) {
            scope.refreshScroll();
          }
        }

        function updateNavLibs() {
          scope.mainLib = _.find(libraryService.librarySummaries, { 'kind' : 'system_main' });
          scope.secretLib = _.find(libraryService.librarySummaries, { 'kind' : 'system_secret' });
          allUserLibs = _.filter(libraryService.librarySummaries, { 'kind' : 'user_created' });

          scope.sortByLastKept();
        }


        //
        // Scope methods.
        //
        scope.addLibrary = function () {
          modalService.open({
            template: 'libraries/manageLibraryModal.tpl.html'
          });
        };

        scope.isActive = function (path) {
          var loc = $location.path();
          return loc === path || util.startsWith(loc, path + '/');
        };


        //
        // Watches and listeners.
        //
        scope.$watch(function () {
          return libraryService.isAllowed();
        }, function (newVal) {
          scope.librariesEnabled = newVal;
          if (scope.librariesEnabled) {
            libraryService.fetchLibrarySummaries().then(updateNavLibs);
          }
        });

        $rootScope.$on('librarySummariesChanged', updateNavLibs);

        $rootScope.$on('changedLibrary', function () {
          if (scope.librariesEnabled) {
            allUserLibs = _.filter(libraryService.librarySummaries, { 'kind' : 'user_created' });
            util.replaceArrayInPlace(scope.userLibsToShow, allUserLibs);
          }
        });

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

        // on resizing window -> trigger new turn -> reset library list height
        w.bind('resize', function () {
          scope.$apply(function () {
            setLibListHeight();
          });
        });


        //
        // Scrolling.
        //

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


        //
        // Filtering.
        //
        var fuseOptions = {
           keys: ['name'],
           threshold: 0.3 // 0 means exact match, 1 means match with anything
        };
        var librarySummarySearch = {};
        var invitedSummarySearch = {};

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

        scope.onFilterChange = function () {
          librarySummarySearch = new Fuse(allUserLibs, fuseOptions);
          invitedSummarySearch = new Fuse(libraryService.invitedSummaries, fuseOptions);
          var term = scope.filter.name;
          var newMyLibs = allUserLibs;
          var newMyInvited = libraryService.invitedSummaries;
          if (term.length) {
            newMyLibs = librarySummarySearch.search(term);
            newMyInvited = invitedSummarySearch.search(term);
          }

          util.replaceArrayInPlace(scope.userLibsToShow, newMyLibs);
          util.replaceArrayInPlace(scope.invitedLibsToShow, newMyInvited);

          return scope.userLibsToShow.concat(scope.invitedLibsToShow);
        };


        //
        // Sorting.
        //
        scope.sortingMenu = { show : false, option : 'last_kept' };

        scope.toggleDropdown = function () {
          scope.sortingMenu.show = !scope.sortingMenu.show;
        };

        $document.on('mousedown', onClick);
        function onClick(event) {
          // click on sort button
          if (angular.element(event.target).closest('.kf-sort-libs-button').length) {
            scope.$apply(scope.toggleDropdown);
            return;
          }
          // click on any dropdown sorting option, have a delay before removing menu
          if (angular.element(event.target).closest('.dropdown-option').length) {
            $timeout( function() {
              scope.sortingMenu.show = false;
            }, 300);
            return;
          }
          // click anywhere else that's not dropdown menu
          if (!angular.element(event.target).closest('.dropdown-menu-content').length) {
            scope.$apply( function() {
              scope.sortingMenu.show = false;
            });
            return;
          }
        }

        scope.sortByName = function () {
          scope.sortingMenu.option = 'name';
          var sortByNameFunc = function(a) {return a.name.toLowerCase(); };
          var libs = _.sortBy(allUserLibs, sortByNameFunc);
          var invited = _.sortBy(libraryService.invitedSummaries, sortByNameFunc);
          util.replaceArrayInPlace(scope.userLibsToShow, libs);
          util.replaceArrayInPlace(scope.invitedLibsToShow, invited);
        };

        scope.sortByNumKeeps = function () {
          scope.sortingMenu.option = 'numKeeps';
          var libs = _.sortBy(allUserLibs, 'numKeeps').reverse();
          var invited = _.sortBy(libraryService.invitedSummaries, 'numKeeps').reverse();
          util.replaceArrayInPlace(scope.userLibsToShow, libs);
          util.replaceArrayInPlace(scope.invitedLibsToShow, invited);
        };

        scope.sortByNumFollowers = function () {
          scope.sortingMenu.option = 'numFollowers';
          var libs = _.sortBy(allUserLibs, 'numFollowers').reverse();
          var invited = _.sortBy(libraryService.invitedSummaries, 'numFollowers').reverse();
          util.replaceArrayInPlace(scope.userLibsToShow, libs);
          util.replaceArrayInPlace(scope.invitedLibsToShow, invited);
        };

        scope.sortByLastViewed = function () {
          scope.sortingMenu.option = 'lastViewed';
          function sortByOptTime(libs) {
            var partition = _.values(
                              _.groupBy(libs, function(lib) {
                                return lib.lastViewed === undefined;
                              })
                            );
            var libsRealTimes = partition[0];
            var libsUndefinedTimes = partition[1];
            return _.sortBy(libsRealTimes, 'lastViewed').reverse().concat(libsUndefinedTimes);
          }
          util.replaceArrayInPlace(scope.userLibsToShow, sortByOptTime(allUserLibs));
          util.replaceArrayInPlace(scope.invitedLibsToShow, sortByOptTime(libraryService.invitedSummaries));
        };

        scope.sortByLastKept = function () {
          scope.sortingMenu.option = 'lastKept';
          function sortByOptTime(libs) {
            var partition = _.values(
                              _.groupBy(libs, function(lib) {
                                return lib.lastKept === undefined;
                              })
                            );
            var libsUndefinedTimes = partition[0];
            var libsRealTimes = partition[1];
            return _.sortBy(libsRealTimes, 'lastKept').reverse().concat(libsUndefinedTimes);
          }
          util.replaceArrayInPlace(scope.userLibsToShow, sortByOptTime(allUserLibs));
          util.replaceArrayInPlace(scope.invitedLibsToShow, sortByOptTime(libraryService.invitedSummaries));
        };
      }
    };
  }
]);
