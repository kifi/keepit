'use strict';

angular.module('kifi')

.controller('ManageTagCtrl', ['tagService', '$scope', '$window', 'libraryService', '$rootScope',
  function (tagService, $scope, $window, libraryService, rootScope) {
    $scope.tagList = tagService.list;
    $scope.tagsToShow = $scope.tagList;

    $scope.selectedSort = '';

    $scope.selectSortName = function() {
      $scope.selectedSort = 'name';
      $scope.tagsToShow = _.sortBy($scope.tagList, 'lowerName');
    };

    $scope.selectSortKeeps = function() {
      $scope.selectedSort = 'keeps';
      $scope.tagsToShow = _.sortBy($scope.tagList, 'keeps').reverse();
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
    var preventClearFilter = false;
    $scope.filter.name = '';
    $scope.focusFilter = function () {
      $scope.isFilterFocused = true;
    };

    $scope.disableClearFilter = function () {
      preventClearFilter = true;
    };

    $scope.enableClearFilter = function () {
      preventClearFilter = false;
    };

    $scope.blurFilter = function () {
      $scope.isFilterFocused = false;
      if (!preventClearFilter) {
        $scope.clearFilter();
      }
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
    };

    $scope.removeTag = function (tag) {
      var choice = $window.confirm('Are you sure you want to delete '+ tag.name + '?');
      if (choice) {
        tagService.remove(tag);
      }
    };

  }
]);
