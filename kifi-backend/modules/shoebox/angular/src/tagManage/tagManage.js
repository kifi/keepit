'use strict';

angular.module('kifi')

.controller('ManageTagCtrl', [
  '$scope', '$window', 'manageTagService', 'libraryService',
  'routeService', '$http', '$location', 'modalService', '$timeout', '$rootScope',
  function (
      $scope, $window, manageTagService, libraryService,
      routeService, $http, $location, modalService, $timeout, $rootScope) {
    $window.document.title = 'Kifi • Manage Your Tags';

    $scope.selected = {};
    $scope.selectedSort = 'name';
    $scope.tagList = [];
    $scope.selectedTag = {};
    $scope.more = false;
    $scope.offset = 0;


    //
    // Smart Scroll
    //
    $scope.$watch('tagList', function (res) {
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


    $scope.$watch('selectedSort', function () {
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

    $scope.changeSelection = function (tag) {
      tag.selected = true;
      $scope.selectedTag = tag;
    };

    $scope.onWidgetExit = function () {
      $scope.selectedTag.selected = false;
    };

    $scope.onWidgetCopyLibraryClicked = function (clickedLibrary) {
      libraryService.copyKeepsFromTagToLibrary(clickedLibrary.id, $scope.selectedTag.name).then(function () {
        modalService.open({
          template: 'tagManage/tagToLibModal.tpl.html',
          modalData: { library : clickedLibrary, action: 'copy' }
        });
        // todo (aaron): call addToLibraryCount accordingly (make sure source libraries do NOT lose keep counts)
        libraryService.fetchLibraryInfos(true);
      })['catch'](function () {
        modalService.open({
          template: 'common/modal/genericErrorModal.tpl.html'
        });
      });
    };

    $scope.onWidgetMoveLibraryClicked = function (clickedLibrary) {
      libraryService.moveKeepsFromTagToLibrary(clickedLibrary.id, $scope.selectedTag.name).then(function () {
        modalService.open({
          template: 'tagManage/tagToLibModal.tpl.html',
          modalData: { library : clickedLibrary, action: 'move' }
        });
        // todo (aaron): call addToLibraryCount accordingly (make sure source libraries lose keep counts)
        libraryService.fetchLibraryInfos(true);
      })['catch'](function () {
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
      removedIndex = _.findIndex($scope.tagsToShow, function(t) { return t === $scope.selectedTag; });
      removedTag = $scope.tagsToShow[removedIndex];
      $scope.tagsToShow.splice(removedIndex, 1);
    };

    var deregisterUndoRemoveTag = $rootScope.$on('undoRemoveTag', function () {
      if (removedIndex > 0) {
        $scope.tagsToShow.splice(removedIndex, 0, removedTag);
        removedIndex = -1;
        removedTag = {};
      }
    });
    $scope.$on('$destroy', deregisterUndoRemoveTag);
  }
]);
