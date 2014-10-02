'use strict';

angular.module('kifi')

.directive('kfNav', [
  '$location', '$window', '$rootScope', '$timeout', 'util', 'friendService', 'modalService', 'tagService', 'profileService', 'libraryService',
  function ($location, $window, $rootScope, $timeout, util, friendService, modalService, tagService, profileService, libraryService) {
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
        var libList = element.find('.kf-nav-lib-users');
        var antiscroll = element.find('.antiscroll-inner');

        libList.on('mousewheel', function(e) {
            var d = e.originalEvent.deltaY;
            var visibleHeight = scrollableLibList.innerHeight();
            var totalHeight = libList.innerHeight();
            var maxScroll = totalHeight - visibleHeight;
            var scroll = antiscroll.scrollTop();
            if ((d < 0 && scroll <= 0) || (d > 0 && scroll >= maxScroll)) {
              e.preventDefault();
            }
        });
        w.bind('resize', function () {
          scope.$apply(function () {
            setLibListHeight();
          });
        });

        function setLibListHeight() {
          if (scrollableLibList.offset()) {
            scrollableLibList.height(w.height() - (scrollableLibList.offset().top - w[0].pageYOffset));
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
            
            $timeout(function() {
              scope.$apply(function () {
                scrollableLibList = element.find('.kf-scrollable-libs');
                setLibListHeight();
              });
            }, 1000);
           
          }
        });

        $rootScope.$on('changedLibrary', function () {
          if (scope.librariesEnabled) {
            scope.mainLib = _.find(scope.librarySummaries, { 'kind' : 'system_main' });
            scope.secretLib = _.find(scope.librarySummaries, { 'kind' : 'system_secret' });
            scope.allUserLibs = _.filter(scope.librarySummaries, { 'kind' : 'user_created' });
            util.replaceArrayInPlace(scope.userLibsToShow, scope.allUserLibs);
            util.replaceArrayInPlace(scope.invitedLibsToShow, scope.allInvitedLibs);
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
              sortLibrariesByName(1);
              break;
            case 'Z-A':
              sortLibrariesByName(-1);
              break;
            case '# Keeps':
              sortLibrariesByNumKeeps();
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

        function sortLibrariesByName(order) {
          var sorting = function(a,b) {
            if (a.name > b.name) {
              return 1;
            } else if (a.name < b.name) {
              return -1;
            } else {
              return 0;
            }
          };

          var libs = scope.allUserLibs.sort(sorting);
          var invited = scope.allInvitedLibs.sort(sorting);
          if (order < 0) {
            libs = libs.reverse();
            invited = invited.reverse();
          }
          util.replaceArrayInPlace(scope.userLibsToShow, libs);
          util.replaceArrayInPlace(scope.invitedLibsToShow, invited);
        }
        
        function sortLibrariesByNumKeeps() {
          var sorting = function(a,b) {
            if (a.numKeeps > b.numKeeps) {
              return -1;
            } else if (a.numKeeps < b.numKeeps) {
              return 1;
            } else {
              return 0;
            }
          };
          var libs = scope.allUserLibs.sort(sorting);
          var invited = scope.allInvitedLibs.sort(sorting);
          util.replaceArrayInPlace(scope.userLibsToShow, libs);
          util.replaceArrayInPlace(scope.invitedLibsToShow, invited);
        }
      }
    };
  }
]);
