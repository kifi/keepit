'use strict';

angular.module('kifi')

.directive('kfLibraryMenu', [
  '$document', '$interval', '$location', '$rootScope', '$window', '$timeout',
  'friendService', 'libraryService', 'modalService', 'profileService', 'routeService', 'tagService', 'util',
  function ($document, $interval, $location, $rootScope, $window, $timeout,
  friendService, libraryService, modalService, profileService, routeService, tagService, util) {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'layout/libraryMenu/libraryMenu.tpl.html',
      link: function (scope , element /*, attrs*/) {
        //
        // Internal data.
        //
        var allUserLibs = [];
        var scrollableLibList = element.find('.kf-scrollable-libs');
        var antiscrollLibList = scrollableLibList.find('.antiscroll-inner');
        var separators = antiscrollLibList.find('.kf-nav-lib-separator');
        var elementCache = {};

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
        function getElement(selector) {
          if (!elementCache[selector] || !elementCache[selector].length) {
            elementCache[selector] = angular.element(selector);
          }

          return elementCache[selector];
        }

        function positionMenu() {
          if (scope.libraryMenu.visible) {
            element.css({'left': Math.max(getElement('.kf-lih-toggle-menu').offset().left - 13, 0) + 'px'});
          }
        }

        function setMenuHeight() {
          var menuHeight = getElement('.kf-nav-lib-users').outerHeight() + 230;
          var maxMenuHeight = Math.floor(($window.innerHeight - getElement('.kf-lih').outerHeight()) * 0.9);
          element.css({'height': Math.min(menuHeight, maxMenuHeight) + 'px'});
        }

        function openMenu() {
          resetSeparators();
          positionMenu();
          scope.changeList();
        }

        function updateNavLibs() {
          scope.mainLib = _.find(libraryService.librarySummaries, { 'kind' : 'system_main' });
          scope.secretLib = _.find(libraryService.librarySummaries, { 'kind' : 'system_secret' });
          allUserLibs = _.reject(libraryService.librarySummaries, libraryService.isSystemLibrary);
          librarySummarySearch = new Fuse(allUserLibs, fuseOptions);
          invitedSummarySearch = new Fuse(libraryService.invitedSummaries, fuseOptions);

          if (scope.libraryMenu.visible) {
            scope.changeList();
          }
        }

        function setStickySeparator() {
          var offset = antiscrollLibList.scrollTop();
          var libItemHeight = 0;
          var separatorHeight = 0;

          if (separators.length === 0) {
            return;
          }
          separatorHeight = separators.eq(0).outerHeight(true);

          var libItems = antiscrollLibList.find('.kf-nav-lib-item');
          libItemHeight = libItems.eq(0).outerHeight(true);

          // set limits based on number of items in myLibs, userLibs or invitedLibs
          var firstLimit, firstLimitOverlay, secondLimit, secondLimitOverlay;
          if (scope.myLibsToShow.length > 0) {
            firstLimit = scope.myLibsToShow.length * libItemHeight;
            firstLimitOverlay = firstLimit + separatorHeight;
            if (scope.userLibsToShow.length > 0) {
              secondLimit = firstLimitOverlay + scope.userLibsToShow.length * libItemHeight;
            } else if (scope.invitedLibsToShow.length > 0) {
              secondLimit = firstLimitOverlay + scope.invitedLibsToShow.length * libItemHeight;
            }
            secondLimitOverlay = secondLimit + separatorHeight;
          } else if (scope.userLibsToShow.length > 0) {
            firstLimit = scope.userLibsToShow.length * libItemHeight;
            firstLimitOverlay = firstLimit + separatorHeight;
            secondLimit = firstLimit + scope.invitedLibsToShow.length * libItemHeight;
            secondLimitOverlay = secondLimit + separatorHeight;
          } else {
            firstLimit = scope.invitedLibsToShow.length * libItemHeight;
            firstLimitOverlay = firstLimit + separatorHeight;
            // no more limits needed
          }

          // If offset is 0, that means that there is no scroll effectively, and we don't want to set any
          // fixed positions yet. This is needed because on menu open after a previous scroll, antiscroll's
          // scroll is automatically triggered, and without this check the first separator will change from
          // position:relative to position:fixed, which results in a visual "pop" of the separator
          // (which itself is due to the fact that a transform that is anything but "none" does not handle
          // position:fixed correctly, and we are using a transform transition during menu open).
          if (offset !== 0) {
            getElement('.kf-nav-lib-users').css('padding-top', '35px');
            fixSeparators(offset, firstLimit, firstLimitOverlay, secondLimit, secondLimitOverlay, separatorHeight);
          }
        }

        function fixSeparators(offset, firstLimit, firstLimitOverlay, secondLimit, secondLimitOverlay, separatorHeight) {
          var stickToMaxTop = 225;
          // all 3 separators properties need to be set because this function is debounced and a user might scroll too fast
          if (offset <= firstLimit) {
            setPositioning(separators[0], 'fixed', stickToMaxTop);
            setPositioning(separators[1], 'relative', 0);
            setPositioning(separators[2], 'relative', 0);

          } else if (offset > firstLimit && offset <= firstLimitOverlay) {
            setPositioning(separators[0], 'fixed', stickToMaxTop - (offset - (firstLimitOverlay - separatorHeight)));
            setPositioning(separators[1], 'relative', 0);
            setPositioning(separators[2], 'relative', 0);

          } else if ( offset > firstLimitOverlay && offset <= secondLimit) {
            setPositioning(separators[0], 'relative', 0);
            setPositioning(separators[1], 'fixed', stickToMaxTop);
            setPositioning(separators[2], 'relative', 0);

          } else if (offset > secondLimit && offset <= secondLimitOverlay) {
            setPositioning(separators[0], 'relative', 0);
            setPositioning(separators[1], 'fixed', stickToMaxTop - (offset - (secondLimitOverlay - separatorHeight)));
            setPositioning(separators[2], 'relative', 0);

          } else {
            setPositioning(separators[0], 'relative', 0);
            setPositioning(separators[1], 'relative', 0);
            setPositioning(separators[2], 'fixed', stickToMaxTop);

          }
        }

        function resetSeparators() {
          getElement('.kf-nav-lib-users').css('padding-top', '0px');
          setPositioning(separators[0], 'relative', 0);
          setPositioning(separators[1], 'relative', 0);
          setPositioning(separators[2], 'relative', 0);
        }

        function setPositioning(dom, position, top) {
          if (dom) {
            dom.style.position = position;
            dom.style.top = top + 'px';
            if (position === 'fixed') {
              dom.style.left = angular.element(dom).parent().offset().left + 'px';
            } else {
              dom.style.left = '0px';
            }
          }
        }

        //
        // Scope methods.
        //

        scope.closeMenu = function () {
          scope.libraryMenu.visible = false;
        };

        scope.isActive = function (path) {
          var loc = $location.path();
          return loc === path || util.startsWith(loc, path + '/');
        };

        scope.redirectTo = function (path) {
          $location.path(path);
        };

        scope.toggleMyLibsFirst = function () {
          scope.sortingMenu.myLibsFirst = !scope.sortingMenu.myLibsFirst;
        };

        scope.getLibraryOwnerProfileUrl = function (library) {
          return routeService.getProfileUrl(library.owner.username);
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

        scope.$watch('libraryMenu.visible', function (visible) {
          if (visible) {
            openMenu();
          }
        });

        // On window resize, if the library menu is open, close it during the
        // resize and reopen after resizing has completed.
        var closedOnResize = false;
        var reopenOnResizeComplete = _.debounce(function () {
          if (closedOnResize) {
            $timeout(function () {
              scope.libraryMenu.visible = true;
              closedOnResize = false;
            });
          }
        }, 500);
        var hideAndReopenOnResize = function () {
          $timeout(function () {
            if (scope.libraryMenu.visible) {
              scope.libraryMenu.visible = false;
              closedOnResize = true;
            }

            $timeout(reopenOnResizeComplete);
          });
        };
        $window.addEventListener('resize', hideAndReopenOnResize);
        scope.$on('$destroy', function () {
          $window.removeEventListener(hideAndReopenOnResize);
        });

        //
        // Scrolling.
        //
        antiscrollLibList.bind('scroll', _.debounce(setStickySeparator, 10));

        antiscrollLibList.on('wheel', function (event) {
          event.originalEvent.kfAllow = this.scrollHeight > this.clientHeight;
        });

        element.on('wheel', function (event) {
          if (!event.originalEvent.kfAllow) {
            event.preventDefault();
          }
        });

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
          getElement('.kf-nav-lib-users').css({visibility: 'hidden'});
          $timeout(function () {
            setMenuHeight();
            getElement('.kf-nav-lib-users').css({visibility: 'visible'});
          });

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
          $timeout(function() {
            antiscrollLibList.scrollTop(0);
            separators = antiscrollLibList.find('.kf-nav-lib-separator');
          });
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
          // On a click outside the menu, close the menu.
          if (scope.libraryMenu.visible && !element[0].contains(event.target) &&
              !getElement('.kf-lih-toggle-menu')[0].contains(event.target)) {
            scope.libraryMenu.visible = false;
          }

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
