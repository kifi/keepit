'use strict';

angular.module('kifi')

.config([
  '$routeProvider',
  function ($routeProvider) {
    $routeProvider
    .when('/:username/:librarySlug', {
      templateUrl: 'libraries/library.tpl.html',
      controller: 'LibraryCtrl'
    });
  }
])

.controller('LibraryCtrl', [
  '$scope', 'keepService', '$routeParams', 'libraryService',
  function ($scope, keepService, $routeParams, libraryService) {
    $scope.keeps = [];

    var username = $routeParams.username;
    var librarySlug = $routeParams.librarySlug;

    var libraryP = libraryService.getLibraryByUserSlug(username, librarySlug);

    libraryP.then(function (library) {
      $scope.library = library;
      $scope.keeps = library.keeps || [];
    });

    $scope.getSingleSelectedKeep = function () {
      return [];
    };

    $scope.hasMore = function () {
      return false; // todo
    };
    $scope.getSubtitle = function () {
      if ($scope.loading) {
        return 'Loading...';
      }

      var subtitle = false; // todo? keepService.getSubtitle($scope.mouseoverCheckAll);
      if (subtitle) {
        return subtitle;
      }

      var numShown = $scope.keeps.length;
      switch (numShown) {
      case 0:
        return 'No Keeps';
      case 1:
        return 'Showing the only Keep';
      case 2:
        return 'Showing both Keeps';
      }
      if (true) { // todo
        return 'Showing all ' + numShown + ' Keeps';
      }
      return 'Showing the ' + numShown + ' latest Keeps';
    };

    $scope.scrollDistance = '100%';
    $scope.scrollDisabled = false;

    $scope.getNextKeeps = function () {
      if ($scope.loading) {
        return;
      }

      $scope.loading = true;
      return libraryService.getLibraryByUserSlug(username, librarySlug).then(function (list) {
        $scope.loading = false;

        if (false) { // todo
          $scope.scrollDisabled = true;
        }

        return list;
      });
    };

    // function initKeepList() {
    //   $scope.scrollDisabled = false;
    //   $scope.getNextKeeps();
    // }

  }
]);
