'use strict';

angular.module('kifi')

.controller('ManageTagCtrl', ['tagService', '$scope', '$window', 'manageTagService',
  function (tagService, $scope, $window, manageTagService) {
    $scope.selectedSort = 'name';
    $scope.num = 0;
    $scope.tagList = manageTagService.list;
    $scope.tagsLoaded = false;
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
      $scope.num += 1;
      manageTagService.getMore($scope.selectedSort);
    };

    $scope.$watch(function () {
        return $scope.selectedSort;
      }, function () {
      manageTagService.getMore($scope.selectedSort);
    });

    $scope.isUniqueTags = function () {
      return _.uniq($scope.tagList).length === $scope.tagList.length;
    };

    ///////////////////////////
    ///// Filtering Stuff /////
    ///////////////////////////
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


    ///////////////////////
    ///// Manage Tags /////
    ///////////////////////

    $scope.convertToLibrary = function (tagName) {
      // first create library with same name
      $window.alert('Creating library ' + tagName);
      /*
      var newLibrary = { name: tagName, slug: tagName, visibility: 'secret' };
      var promise = libraryService.createLibrary(newLibrary).then(function (resp) {
        libraryService.fetchLibrarySummaries(true).then(function () {
          $rootScope.$emit('changedLibrary');
        });
      });

      // then copy keeps to that library
      $window.alert('Copying keeps to ' + tagName);
      var libraryId = _.find(libraryService.librarySummaries, function (lib) {
        return lib.name === 'tagName';
      }).id;
      libraryService.copyKeepsFromTagToLibrary(libraryId, tagName);
      */
    };

    $scope.removeTag = function (tag) {
      var choice = $window.confirm('Are you sure you want to delete '+ tag.name + '?');
      if (choice) {
        tagService.remove(tag);
      }
    };

  }
]);
