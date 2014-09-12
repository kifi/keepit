'use strict';

angular.module('kifi')

.controller('TagKeepsCtrl', [
  '$scope', 'keepActionService', 'keepDecoratorService', 'tagService', '$routeParams', '$window',
  function ($scope, keepActionService, keepDecoratorService, tagService, $routeParams, $window) {
    //
    // Internal data.
    //
    var tagId = $routeParams.tagId || '';
    var selectedCount = 0;


    //
    // Scope data.
    //
    $scope.tagKeeps = [];
    $scope.hasLoaded = false;
    $scope.hasMore = true;
    $scope.scrollDistance = '100%';
    $scope.loading = false;


    //
    // Scope methods.
    //
    $scope.getNextKeeps = function () {
      if ($scope.loading) {
        return;
      }

      $scope.loading = true;

      var lastKeepId = $scope.tagKeeps.length ? _.last($scope.tagKeeps).id : null;
      return keepActionService.getKeepsByTagId(tagId, lastKeepId).then(function (result) {
        var rawKeeps = result.keeps;

        rawKeeps.forEach(function (rawKeep) {
          var keep = new keepDecoratorService.Keep(rawKeep);
          keep.buildKeep(keep);
          keep.makeKept();

          $scope.tagKeeps.push(keep);
        });

        $scope.hasMore = !!result.mayHaveMore;
        $scope.loading = false;
        $scope.hasLoaded = true;

        return $scope.tagKeeps;
      });
    };

    $scope.getSubtitle = function () {
      if ($scope.loading) {
        return 'Loading...';
      }

      // If there are selected keeps, display the number of keeps
      // in the subtitle.
      if (selectedCount > 0) {
        return (selectedCount === 1) ? '1 Keep selected' : selectedCount + ' Keeps selected';
      }

      var numShown = $scope.tagKeeps.length;
      switch (numShown) {
        case 0:
          return 'No Keeps in this tag';
        case 1:
          return 'Showing the only Keep in this tag';
        case 2:
          return 'Showing both Keeps in this tag';
        default:
          if (!$scope.hasMore) {
            return 'Showing all ' + numShown + ' Keeps in this tag';
          }
          return 'Showing your ' + numShown + ' latest Keeps in this tag';
      }
    };

    $scope.updateSelectedCount = function (numSelected) {
      selectedCount = numSelected;
    };

    $scope.showEmptyState = function () {
      return $scope.tagKeeps.length === 0 && !$scope.hasMore;
    };


    //
    // On TagKeepsCtrl initialization.
    //
    tagService.promiseById(tagId).then(function (tag) {
      $window.document.title = 'Kifi • ' + tag.name;
      $scope.tag = tag || null;
    });

    $scope.getNextKeeps();      
  }
]);
