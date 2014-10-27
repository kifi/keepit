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

          var newList = sortLibraries(allUserLibs, libraryService.invitedSummaries);
          scope.userLibsToShow = newList[0];
          scope.invitedLibsToShow  = newList[1];
          librarySummarySearch = new Fuse(allUserLibs, fuseOptions);
          invitedSummarySearch = new Fuse(libraryService.invitedSummaries, fuseOptions);

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

        var deregisterLibrarySummaries = $rootScope.$on('librarySummariesChanged', updateNavLibs);
        scope.$on('$destroy', deregisterLibrarySummaries);

        var deregisterChangedLibrary = $rootScope.$on('changedLibrarySorting', function() {
          scope.sortingMenu.option = profileService.prefs.library_sorting_pref || 'last_kept';
          updateNavLibs();
        });
        scope.$on('$destroy', deregisterChangedLibrary);

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
          if (scope.sortingMenu.option) {
            scope.changeList();
            scope.turnDropdownOff();
            profileService.savePrefs( { library_sorting_pref: scope.sortingMenu.option });
          }
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
          if (scope.filter.name === '') {
            scope.isFilterFocused = false;
          }
        };

        scope.clearFilter = function () {
          scope.filter.name = '';
          scope.onFilterChange();
          scope.blurFilter();
        };

        scope.onFilterChange = function () {
          return scope.changeList();
        };


        scope.changeList = function () {
          var term = scope.filter.name;
          var newLibs = allUserLibs;
          var newInvited = libraryService.invitedSummaries;
          if (term.length) {
            newLibs = librarySummarySearch.search(term);
            newInvited = invitedSummarySearch.search(term);
          }
          var newList = sortLibraries(newLibs, newInvited);
          newLibs = newList[0];
          newInvited = newList[1];

          scope.userLibsToShow = newLibs;
          scope.invitedLibsToShow = newInvited;
          return scope.userLibsToShow.concat(scope.invitedLibsToShow);
        };

        function sortLibraries(userLibs, invitedLibs) {
          var newLibs = userLibs;
          var newInvited = invitedLibs;
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
          return [newLibs, newInvited];
        }

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
        };
        scope.turnDropdownOff = function () {
          scope.sortingMenu.show = false;
        };

        function onClick(event) {
          if (angular.element(event.target).closest('.kf-sort-libs-button').length) {
            scope.$apply( function() {
              scope.toggleDropdown();
            });
            return;
          }

          // click anywhere else that's not dropdown menu
          if (!angular.element(event.target).closest('.dropdown-menu-content').length) {
            scope.$apply( function() {
              scope.turnDropdownOff();
            });
            return;
          }
        }
        $document.on('mousedown', onClick);

        // Cleaner upper
        scope.$on('$destroy', function() {
          $document.off('mousedown', onClick);
        });

      }
    };
  }
]);
