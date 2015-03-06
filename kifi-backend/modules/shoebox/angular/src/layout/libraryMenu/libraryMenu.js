'use strict';

angular.module('kifi')

.directive('kfLibraryMenu', [
  '$document', '$interval', '$location', '$rootScope', '$window', '$timeout',
  'libraryService', 'modalService', 'profileService', 'util',
  function (
      $document, $interval, $location, $rootScope, $window, $timeout,
      libraryService, modalService, profileService, util) {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'layout/libraryMenu/libraryMenu.tpl.html',
      link: function (scope, element) {
        //
        // Internal data.
        //
        var allUserLibs = [];  // own and following
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
          var menuHeight = getElement('.kf-nav-lib-users')[0].scrollHeight + 230;
          var maxMenuHeight = Math.floor(($window.innerHeight - getElement('.kf-lih').outerHeight()) * 0.9);
          element.css({'height': Math.min(menuHeight, maxMenuHeight) + 'px'});
        }

        function openMenu() {
          resetSeparators();
          positionMenu();
          scope.changeList();
          if (!scrollableLibList.data('antiscroll')) {
            element.on('transitionend', function f(e) {
              if (e.target === this) {
                element.off('transitionend', f);
                scrollableLibList.antiscroll({autoHide: false});
              }
            });
          }
        }

        function updateNavLibs() {
          scope.mainLib = libraryService.getSysMainInfo();
          scope.secretLib = libraryService.getSysSecretInfo();
          allUserLibs = _.reject(libraryService.getOwnInfos(), libraryService.isLibraryMainOrSecret)
            .concat(libraryService.getFollowingInfos());
          librarySummarySearch = new Fuse(allUserLibs, fuseOptions);
          invitedSummarySearch = new Fuse(libraryService.getInvitedInfos(), fuseOptions);

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
            fixSeparators(offset, firstLimit, firstLimitOverlay, secondLimit, secondLimitOverlay, separatorHeight);
          }
        }

        function fixSeparators(offset, firstLimit, firstLimitOverlay, secondLimit, secondLimitOverlay, separatorHeight) {
          getElement('.kf-nav-lib-users').css('padding-top', separatorHeight);
          // all 3 separators need to be positioned because this function is debounced and a user might scroll fast
          var sep;
          if (offset <= firstLimit) {
            sep = separators.eq(0).css({position: 'absolute', top: 0});
          } else if (offset > firstLimit && offset <= firstLimitOverlay) {
            sep = separators.eq(0).css({position: 'absolute', top: 0 - (offset - (firstLimitOverlay - separatorHeight))});
          } else if ( offset > firstLimitOverlay && offset <= secondLimit) {
            sep = separators.eq(1).css({position: 'absolute', top: 0});
          } else if (offset > secondLimit && offset <= secondLimitOverlay) {
            sep = separators.eq(1).css({position: 'absolute', top: 0 - (offset - (secondLimitOverlay - separatorHeight))});
          } else {
            sep = separators.eq(2).css({position: 'absolute', top: 0});
          }
          separators.not(sep).css({position: '', top: ''});
        }

        function resetSeparators() {
          getElement('.kf-nav-lib-users').css('padding-top', '0px');
          separators.css({position: '', top: ''});
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

        //
        // Watches and listeners.
        //
        var deregisterLibrarySummaries = $rootScope.$on('libraryInfosChanged', updateNavLibs);
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
          var newLibs = term ? librarySummarySearch.search(term) : allUserLibs;
          var newInvited = term ? invitedSummarySearch.search(term) : libraryService.getInvitedInfos();
          var newLists = sortLibraries(newLibs, newInvited);
          scope.userLibsToShow = newLists[0];
          scope.invitedLibsToShow = newLists[1];
          scope.myLibsToShow = newLists[2]; // should be [] if myLibsFirst false

          var libUsers = getElement('.kf-nav-lib-users').css({visibility: 'hidden'});
          $timeout(function () {
            setMenuHeight();
            libUsers.css({visibility: 'visible'});
            scrollableLibList.scrollTop(0);
            (scrollableLibList.data('antiscroll') || {refresh: angular.noop}).refresh();
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
