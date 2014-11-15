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
        scope.mainLib = {};
        scope.secretLib = {};
        scope.myLibsToShow = [];
        scope.userLibsToShow = [];
        scope.invitedLibsToShow = [];
        scope.sortingMenu = { show : false, option : '', myLibsFirst : true };

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
          scope.$broadcast('refreshScroll');
        }

        function updateNavLibs() {
          scope.mainLib = _.find(libraryService.librarySummaries, { 'kind' : 'system_main' });
          scope.secretLib = _.find(libraryService.librarySummaries, { 'kind' : 'system_secret' });
          allUserLibs = _.filter(libraryService.librarySummaries, { 'kind' : 'user_created' });

          var newLists = sortLibraries(allUserLibs, libraryService.invitedSummaries);
          scope.userLibsToShow = newLists[0];
          scope.invitedLibsToShow  = newLists[1];
          scope.myLibsToShow = newLists[2];  // should be [] if myLibsFirst false
          librarySummarySearch = new Fuse(allUserLibs, fuseOptions);
          invitedSummarySearch = new Fuse(libraryService.invitedSummaries, fuseOptions);

          scope.$broadcast('refreshScroll');
        }

        // Temp callout method. Remove after most users know about libraries. (Oct 26 2014)
        var calloutName = 'library_callout_shown';
        scope.showCallout = function () {
          return profileService.prefs.site_intial_show_library_intro && !profileService.prefs[calloutName];
        };
        scope.closeCallout = function () {
          var save = { 'site_show_library_intro': false };
          save[calloutName] = true;
          profileService.prefs[calloutName] = true;
          profileService.savePrefs(save);
        };


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
        var deregisterLibrarySummaries = $rootScope.$on('librarySummariesChanged', updateNavLibs);
        scope.$on('$destroy', deregisterLibrarySummaries);

        var deregisterChangedLibrary = $rootScope.$on('changedLibrarySorting', function() {
          scope.sortingMenu.option = profileService.prefs.library_sorting_pref || 'last_kept';
          updateNavLibs();
        });
        scope.$on('$destroy', deregisterChangedLibrary);

        var deregisterLibraryVisited = $rootScope.$on('lastViewedLib', function (e, lib) {
          if (lib.name) {
            var idx = _.findIndex(allUserLibs, { 'name' : lib.name });
            if (allUserLibs[idx]) {
              allUserLibs[idx].lastViewed = Date.now();
            }
            if (scope.sortingMenu.option === 'last_viewed') {
              scope.changeList();
            }
          }
        });
        scope.$on('$destroy', deregisterLibraryVisited);

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
            if (scope.sortingMenu.option !== profileService.prefs.library_sorting_pref) {
              profileService.savePrefs( { library_sorting_pref: scope.sortingMenu.option });
            }
          }
        });

        scope.$watch(function() {
          return scope.sortingMenu.myLibsFirst;
        }, function () {
          scope.changeList();
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
           threshold: 0.3  // 0 means exact match, 1 means match with anything.
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
          var newLists = sortLibraries(newLibs, newInvited);
          scope.userLibsToShow = newLists[0];
          scope.invitedLibsToShow = newLists[1];
          scope.myLibsToShow = newLists[2]; // should be [] if myLibsFirst false

          scope.$broadcast('refreshScroll');
          return scope.userLibsToShow.concat(scope.invitedLibsToShow).concat(scope.myLibsToShow);
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
          var newMyLibs = [];
          if (scope.sortingMenu.myLibsFirst && newLibs.length > 0) {
            var split = _.groupBy(newLibs, function(lib) { return lib.isMine ? 'mine' : 'notMine'; });
            newLibs = split.notMine || [];
            newMyLibs = split.mine || [];
          }
          return [newLibs, newInvited, newMyLibs];
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
