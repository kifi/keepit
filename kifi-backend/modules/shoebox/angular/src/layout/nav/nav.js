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

        //
        // Scope data.
        //
        scope.librariesEnabled = false;
        scope.mainLib = {};
        scope.secretLib = {};
        scope.userLibsToShow = [];
        scope.invitedLibsToShow = [];
        scope.sortingMenu = { show : false, option : '' };

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

          scope.userLibsToShow = allUserLibs;
          scope.invitedLibsToShow = libraryService.invitedSummaries;
          scope.sortingMenu.option = 'last_kept';

          scope.$broadcast('refreshScroll');
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

        scope.$watch(function () {
            return scope.sortingMenu.option;
          }, function () {
          scope.changeList();
          scope.turnDropdownOff();
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
        scope.filter.name = '';

        scope.focusFilter = function () {
          scope.isFilterFocused = true;
        };

        scope.blurFilter = function () {
          scope.isFilterFocused = false;
        };

        scope.clearFilter = function () {
          scope.filter.name = '';
          scope.onFilterChange();
        };

        scope.onFilterChange = function () {
          return scope.changeList();
        };


        scope.changeList = function () {
          librarySummarySearch = new Fuse(allUserLibs, fuseOptions);
          invitedSummarySearch = new Fuse(libraryService.invitedSummaries, fuseOptions);

          var term = scope.filter.name;
          var newLibs = allUserLibs;
          var newInvited = libraryService.invitedSummaries;
          if (term.length) {
            newLibs = librarySummarySearch.search(term);
            newInvited = invitedSummarySearch.search(term);
          }

          switch (scope.sortingMenu.option) {
            case 'name':
              var sortByNameFunc = function(a) {return a.name.toLowerCase(); };
              newLibs = _.sortBy(newLibs, sortByNameFunc);
              newInvited = _.sortBy(newInvited, sortByNameFunc);
              break;

            case 'num_keeps':
              newLibs = _.sortBy(newLibs, 'numKeeps').reverse();
              newInvited = _.sortBy(newInvited, 'numKeeps').reverse();
              break;

            case 'num_followers':
              newLibs = _.sortBy(newLibs, 'numFollowers').reverse();
              newInvited = _.sortBy(newInvited, 'numFollowers').reverse();
              break;

            case 'last_viewed':
              newLibs = sortByViewed(newLibs);
              newInvited = sortByViewed(newInvited);
              break;
            case 'last_kept':
              newLibs = sortByKept(newLibs);
              newInvited = sortByKept(newInvited);
              break;
          }
          scope.userLibsToShow = newLibs;
          scope.invitedLibsToShow = newInvited;
          return scope.userLibsToShow.concat(scope.invitedLibsToShow);
        };

        function sortByKept(libs) {
          var partition = _.groupBy(libs, function(lib) {
                              return (lib.lastKept === undefined) ? 'undefinedTimes' : 'definedTimes';
                            });
          var libsUndefinedTimes = partition.undefinedTimes;
          if (!libsUndefinedTimes) {
            libsUndefinedTimes = [];
          }
          var libsRealTimes = partition.definedTimes;
          return _.sortBy(libsRealTimes, 'lastKept').reverse().concat(libsUndefinedTimes);
        }

        function sortByViewed(libs) {
          var partition = _.groupBy(libs, function(lib) {
                              return (lib.lastViewed === undefined) ? 'undefinedTimes' : 'definedTimes';
                            });
          var libsUndefinedTimes = partition.undefinedTimes;
          if (!libsUndefinedTimes) {
            libsUndefinedTimes = [];
          }
          var libsRealTimes = partition.definedTimes;
          return _.sortBy(libsRealTimes, 'lastViewed').reverse().concat(libsUndefinedTimes);
        }

        //
        // Sorting.
        //
        scope.toggleDropdown = function () {
          if (scope.sortingMenu.show === true) {
            scope.turnDropdownOff();
          } else {
            scope.turnDropdownOn();
          }
        };
        scope.turnDropdownOn = function () {
          scope.sortingMenu.show = true;
          $document.on('mousedown', onClick);
        };
        scope.turnDropdownOff = function () {
          scope.sortingMenu.show = false;
          $document.off('mousedown', onClick);
        };

        function onClick(event) {
          // click anywhere else that's not dropdown menu
          if (!angular.element(event.target).closest('.dropdown-menu-content').length) {
            scope.$apply( function() {
              scope.turnDropdownOff();
            });
            return;
          }
        }

        // Cleaner upper
        scope.$on('$destroy', function() {
          $document.off('mousedown', onClick);
        });
      }
    };
  }
]);
