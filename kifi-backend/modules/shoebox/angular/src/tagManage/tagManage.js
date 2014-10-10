'use strict';

angular.module('kifi')

.controller('ManageTagCtrl', ['tagService', '$scope', '$window', 'manageTagService', 'libraryService', 'routeService', '$http',
  function (tagService, $scope, $window, manageTagService, libraryService, routeService, $http) {
    $scope.selectedSort = 'name';

    $scope.libraries = [];
    $scope.selected = {};

    $scope.tagList = manageTagService.list;
    $scope.tagsLoaded = false;
    $scope.selectedTagName = '';

    //
    // Smart Scroll
    //
    $scope.$watch(function () {
      return manageTagService.list.length || !manageTagService.hasMore();
    }, function (res) {
      if (res) {
        $scope.tagsLoaded = true;
        $scope.tagsToShow = $scope.tagList;
      }
    });
    $scope.tagsScrollDistance = '100%';
    $scope.isTagScrollDisabled = function () {
      return !manageTagService.hasMore();
    };
    $scope.manageTagScrollNext = function () {
      manageTagService.getMore($scope.selectedSort);
    };

    $scope.$watch(function () {
      return $scope.selectedSort;
    }, function () {
      manageTagService.reset();
      manageTagService.getMore($scope.selectedSort);
    });

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
          $scope.selection.library = _.find($scope.libraries, { 'name': 'Main Library' });
        });
      }
    });

    $scope.clickAction = function () {
      if ($scope.selectedTagName !== '') {
        var data = {};
        var config = {};
        $http.post(routeService.copyKeepsFromTagToLibrary($scope.selection.library.id, $scope.selectedTagName), data, config);
      }
    };

    $scope.changeSelection = function (name) {
      $scope.selectedTagName = name;
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

  }
]);
