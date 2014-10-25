'use strict';

angular.module('kifi')

.controller('ManageTagCtrl', ['tagService', '$scope', '$window', 'manageTagService', 'libraryService',
            'routeService', '$http', '$location', 'modalService', '$timeout', '$rootScope',
  function (tagService, $scope, $window, manageTagService, libraryService,
              routeService, $http, $location, modalService, $timeout, $rootScope) {
    $scope.librariesEnabled = false;
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

    //
    // Watchers & Listeners
    //
    $scope.$watch(function () {
      return libraryService.isAllowed();
    }, function (newVal) {
      $scope.librariesEnabled = newVal;
      if ($scope.librariesEnabled) {
        libraryService.fetchLibrarySummaries().then(function () {
          $scope.libraries = _.filter(libraryService.librarySummaries, function (lib) {
            return lib.access !== 'read_only';
          });
          $scope.librarySelection = {};
          $scope.librarySelection.library = _.find($scope.libraries, { 'kind': 'system_main' });
          $scope.libSelectTopOffset = false;  // This overrides the scope.libSelectTopOffset set by MainCtrl.js
        });
      }
    });

    $scope.$watch(function() {
      return $window.innerWidth;
    }, function (width) {
      if (width < 1220) {
        $scope.copyToLibraryDisplay = 'Copy';
        $scope.moveToLibraryDisplay = 'Move';
      } else {
        $scope.copyToLibraryDisplay = 'Copy to Library';
        $scope.moveToLibraryDisplay = 'Move to Library';
      }
    });

    $rootScope.$on('librarySummariesChanged', function () {
      $scope.libraries = _.filter(libraryService.librarySummaries, function (lib) {
        return lib.access !== 'read_only';
      });
    });

    $rootScope.$emit('libraryUrl', {});


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
    $scope.copyToLibraryDisplay = 'Copy To Library';
    $scope.moveToLibraryDisplay = 'Move To Library';
    $scope.actionToLibrary = '';

    $scope.changeSelection = function (tag, action) {
      $scope.selectedTag = tag;
      $scope.actionToLibrary = action;
    };

    // click action when selecting a library from widget
    $scope.clickAction = function () {
      var tagName = encodeURIComponent($scope.selectedTag.name);

      var tagActionResult;
      if ($scope.actionToLibrary === 'copy') {
        tagActionResult = libraryService.copyKeepsFromTagToLibrary($scope.librarySelection.library.id, tagName).then(function () {
          modalService.open({
            template: 'tagManage/tagToLibModal.tpl.html',
            modalData: { library : $scope.librarySelection.library, action: $scope.actionToLibrary }
          });
          // todo (aaron): call addToLibraryCount accordingly (make sure source libraries do NOT lose keep counts)
          libraryService.fetchLibrarySummaries(true);
        });
      } else {
        tagActionResult = libraryService.moveKeepsFromTagToLibrary($scope.librarySelection.library.id, tagName).then(function () {
          modalService.open({
            template: 'tagManage/tagToLibModal.tpl.html',
            modalData: { library : $scope.librarySelection.library, action: $scope.actionToLibrary }
          });
          // todo (aaron): call addToLibraryCount accordingly (make sure source libraries lose keep counts)
          libraryService.fetchLibrarySummaries(true);
        });
      }
      tagActionResult['catch'](function () {
        modalService.open({
          template: 'common/modal/genericErrorModal.tpl.html'
        });
      });
    };

    $scope.showRemoveTagModal = function (tag) {
      $scope.changeSelection(tag);
      $scope.numKeepsInTag = tag.keeps;
      modalService.open({
        template: 'tagManage/deleteTagModal.tpl.html',
        scope: $scope
      });
    };

    var removedIndex = -1;
    var removedTag = {};
    $scope.deleteTag = function () {
      tagService.remove($scope.selectedTag);
      removedIndex = _.findIndex($scope.tagsToShow, function(t) { return t === $scope.selectedTag; });
      removedTag = $scope.tagsToShow[removedIndex];
      $scope.tagsToShow.splice(removedIndex, 1);
    };

    var undoRemoveTagHandler = $rootScope.$on('undoRemoveTag', function () {
      if (removedIndex > 0) {
        $scope.tagsToShow.splice(removedIndex, 0, removedTag);
        removedIndex = -1;
        removedTag = {};
      }
    });
    $scope.$on('$destroy', undoRemoveTagHandler);

  }
]);
