'use strict';

angular.module('kifi')

.controller('ManageTagCtrl', ['tagService', '$scope',
  function (tagService, $scope) {
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

    $scope.convertToLibrary = function () {

    };

    $scope.removeTag = function (tag) {
      tagService.remove(tag);
    };

  }
]);
