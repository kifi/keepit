'use strict';

angular.module('kifi')

.controller('LibraryCtrl', [
  '$scope', 'keepService', '$routeParams', 'libraryService', 'util', '$rootScope', '$location',
  function ($scope, keepService, $routeParams, libraryService, util, $rootScope, $location) {
    $scope.keeps = [];
    $scope.library = {};
    var username = $routeParams.username;
    var librarySlug = $routeParams.librarySlug;

    $scope.manageLibrary = function () {
      libraryService.libraryState = {
        library: $scope.library,
        returnAction: function (resp) {
          libraryService.getLibraryById($scope.library.id, true).then(function (data) {
            libraryService.getLibraryByUserSlug(username, data.library.slug, true);
            if (data.library.slug !== librarySlug) {
              $location.path('/' + username + '/' + data.library.slug);
            }
          });
        }
      };
      $rootScope.$emit('showGlobalModal', 'manageLibrary');
    };

    var libraryP = libraryService.getLibraryByUserSlug(username, librarySlug);

    // librarySummaries has a few of the fields we need to draw the library.
    // Attempt to pre-populate the library object while we wait
    if (libraryService.librarySummaries) {
      var lib = _.find(libraryService.librarySummaries, function (elem) {
        return elem.url === '/' + username + '/' + librarySlug;
      });
      if (lib) {
        util.replaceObjectInPlace($scope.library, lib);
      }
    }

    $scope.loading = true;
    libraryP.then(function (library) {
      _.forEach(library.keeps, keepService.buildKeep);
      $scope.library = library;
      $scope.keeps.push.apply($scope.keeps, library.keeps);
      $scope.loading = false;
    });

    $scope.hasMore = function () {
      return $scope.loading || ($scope.keeps && $scope.keeps.length < $scope.library.numKeeps);
    };
    $scope.getSubtitle = function () {
      if ($scope.loading || !$scope.library) {
        return 'Loading...';
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
      return 'Showing ' + numShown + ' of ' + $scope.library.numKeeps + ' Keeps';
    };

    $scope.scrollDistance = '100%';
    $scope.scrollDisabled = false;

    $scope.getNextKeeps = function () {
      if ($scope.loading || !$scope.library || $scope.keeps.length === 0) {
        return;
      }

      $scope.loading = true;
      return libraryService.getKeepsInLibrary($scope.library.id, $scope.keeps.length).then(function (res) {
        $scope.loading = false;
        // $scope.scrollDisabled = true;
        if (res.keeps) {
          _.forEach(res.keeps, keepService.buildKeep);
          $scope.keeps.push.apply($scope.keeps, res.keeps);
        }

        if ($scope.keeps.length >= $scope.library.numKeeps) {
          $scope.scrollDisabled = true;
        }

        return res;
      });
    };

  }
]);
