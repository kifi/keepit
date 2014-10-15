'use strict';

angular.module('kifi')

.controller('ManageTagCtrl', ['tagService', '$scope', '$window', 'manageTagService', 'libraryService',
            'routeService', '$http', '$location', 'modalService', '$timeout', '$rootScope',
  function (tagService, $scope, $window, manageTagService, libraryService,
              routeService, $http, $location, modalService, $timeout, $rootScope) {
    $scope.libraries = [];
    $scope.selected = {};

    $scope.selectedSort = 'name';
    $scope.tagList = [];
    $scope.selectedTag = {};
    $scope.more = false;
    $scope.offset = 0;

    //
    // Smart Scroll
    //
    $scope.$watch(function () {
      return $scope.tagList;
    }, function (res) {
      if (res) {
        $scope.tagsToShow = $scope.tagList;
      }
    });
    $scope.tagsScrollDistance = '100%';
    $scope.isTagScrollDisabled = function () {
      return !$scope.more;
    };
    $scope.manageTagScrollNext = function () {
      getPage();
    };


    $scope.$watch(function () {
      return $scope.selectedSort;
    }, function () {
      if ($scope.filter.name === '') {
        $scope.tagList.length = 0;
        $scope.offset = 0;
        $scope.more = true;
        manageTagService.reset();
        getPage();
      } else {
        $scope.tagsToShow = localSortTags($scope.tagsToShow);
      }
    });

    var loading = false;
    function getPage() {
      if (loading) { return; }
      loading = true;
      manageTagService.getMore($scope.selectedSort, $scope.offset).then(function (tags) {
        loading = false;
        if (tags.length === 0) {
          $scope.more = false;
        } else {
          $scope.more = true;
          $scope.offset += 1;
          $scope.tagList.push.apply($scope.tagList, tags);
        }
      });
    }

    // Watchers & Listeners
    $scope.librariesEnabled = false;
    $scope.libraries = [];

    $scope.$watch(function () {
      return libraryService.isAllowed();
    }, function (newVal) {
      $scope.librariesEnabled = newVal;
      if ($scope.librariesEnabled) {
        libraryService.fetchLibrarySummaries().then(function () {
          $scope.libraries = _.filter(libraryService.librarySummaries, function (lib) {
            return lib.access !== 'read_only';
          });
          $scope.selection = $scope.selection || {};
          $scope.selection.library = _.find($scope.libraries, { 'kind': 'system_main' });
        });
      }
    });

    $rootScope.$on('changedLibrary', function () {
      $scope.libraries = _.filter(libraryService.librarySummaries, function (lib) {
        return lib.access !== 'read_only';
      });
    });

    $scope.clickAction = function () {
      var tagName = encodeURIComponent($scope.selectedTag.name);
      libraryService.copyKeepsFromTagToLibrary($scope.selection.library.id, tagName).then(function () {
        modalService.open({
          template: 'tagManage/tagToLibModal.tpl.html',
          modalData: { library : $scope.selection.library }
        });
        libraryService.addToLibraryCount($scope.selection.library.id, $scope.selectedTag.keeps);
      })['catch'](function () {
        modalService.open({
          template: 'common/modal/genericErrorModal.tpl.html'
        });
      });
    };

    $scope.changeSelection = function (tag) {
      $scope.selectedTag = tag;
    };

    //
    // Filtering Stuff
    //
    $scope.filter = {};
    $scope.filter.name = '';
    $scope.isFilterFocused = false;

    $scope.focusFilter = function () {
      $scope.isFilterFocused = true;
    };

    $scope.blurFilter = function () {
      $scope.isFilterFocused = false;
    };

    $scope.clearFilter = function () {
      $scope.filter.name = '';
      $scope.tagsToShow = $scope.tagList;
      $scope.more = true;
    };

    $scope.onFilterChange = _.debounce(function () {
      if ($scope.filter.name === '') {
        $timeout($scope.clearFilter, 0);
        return $scope.tagList;
      }
      $scope.more = false;
      manageTagService.search($scope.filter.name).then(function (tags) {
        $scope.tagsToShow = localSortTags(tags);
        return $scope.tagsToShow;
      });
    }, 200, {
      leading: true
    });

    function localSortTags(tags) {
      var sortedTags = tags;
      if ($scope.selectedSort === 'name') {
        sortedTags = _.sortBy(sortedTags, function(t) {
          return t.name.toLowerCase();
        }).reverse();
      } else if ($scope.selectedSort === 'num_keeps') {
        sortedTags = _.sortBy(sortedTags, function(t) {
          return t.keeps;
        }).reverse();
      }
      return sortedTags;
    }

    //
    // Manage Tags
    //
    $scope.removeTag = function (tag) {
      modalService.open({
        template: 'tagManage/deleteTagModal.tpl.html',
        modalData: { tag: tag, tagsToShow: $scope.tagsToShow }
      });
    };

  }
]);
