'use strict';

angular.module('kifi')

.controller('ManageTagCtrl', ['tagService', '$scope', '$window', 'manageTagService', 'libraryService', 'routeService', '$http', '$location', 'modalService',
  function (tagService, $scope, $window, manageTagService, libraryService, routeService, $http, $location, modalService) {
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
      $scope.tagList.length = 0;
      $scope.offset = 0;
      $scope.more = true;
      manageTagService.reset();
      getPage();
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
    // Keeping to Library
    //
    $scope.librariesEnabled = false;
    $scope.libraries = [];

    $scope.$watch(function () {
      return libraryService.isAllowed();
    }, function (newVal) {
      $scope.librariesEnabled = newVal;
      if ($scope.librariesEnabled) {
        libraryService.fetchLibrarySummaries().then(function () {
          $scope.libraries = _.filter(libraryService.librarySummaries, function(lib) {
            return lib.access !== 'read_only';
          });
          $scope.selection = $scope.selection || {};
          $scope.selection.library = _.find($scope.libraries, { 'kind': 'system_main' });
        });
      }
    });

    $scope.clickAction = function () {
      libraryService.copyKeepsFromTagToLibrary($scope.selection.library.id, $scope.selectedTag.name).then(function () {
        libraryService.addToLibraryCount($scope.selection.library.id, $scope.selectedTag.keeps);
        libraryService.getLibraryById($scope.selection.library.id, true); // invalidates cache
      });
      modalService.open({
        template: 'tagManage/tagToLibModal.tpl.html',
        modalData: { library : $scope.selection.library }
      });
    };

    $scope.changeSelection = function (tag) {
      $scope.selectedTag = tag;
    };

    //
    // Filtering Stuff
    //
    var fuseOptions = {
       keys: ['name'],
       threshold: 0.3 // 0 means exact match, 1 means match with anything
    };
    var tagSearch = {};
    $scope.filter = {};
    $scope.isFilterFocused = false;
    $scope.filter.name = '';
    $scope.focusFilter = function () {
      $scope.isFilterFocused = true;
    };

    $scope.blurFilter = function () {
      $scope.isFilterFocused = false;
    };

    $scope.clearFilter = function () {
      $scope.filter.name = '';
      $scope.onFilterChange();
    };

    $scope.onFilterChange = function () {
      tagSearch = new Fuse($scope.tagList, fuseOptions);
      var term = $scope.filter.name;
      var filterTags = $scope.tagList;
      if (term.length) {
        filterTags = tagSearch.search(term);
      }
      $scope.tagsToShow = filterTags;

      return $scope.tagsToShow;
    };


    //
    // Manage Tags
    //
    $scope.removeTag = function (tag) {
      // todo (aaron): Get a nicer looking window thing
      var choice = $window.confirm('Are you sure you want to delete '+ tag.name + '?');
      if (choice) {
        tagService.remove(tag);
        _.remove($scope.tagsToShow, function(t) { return t === tag; });
      }
    };

    $scope.navigateToTag = function (tagName) {
      $location.path('/find').search('q','tag:' + tagName);
    };

  }
]);
