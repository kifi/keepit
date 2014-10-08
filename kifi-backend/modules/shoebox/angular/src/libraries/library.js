'use strict';

angular.module('kifi')

.controller('LibraryCtrl', [
  '$scope', '$rootScope', '$location', '$routeParams', 'keepDecoratorService', 'libraryService',
  'modalService', 'profileService', 'util',
  function ($scope, $rootScope, $location, $routeParams, keepDecoratorService, libraryService,
            modalService, profileService, util) {
    //
    // Internal data.
    //
    var selectedCount = 0;
    var prePopulated = false;
    var authToken = $location.search()['authToken'] || $location.search()['authCode'] || $location.search()['accessToken'] || '';
    //                   ↑↑↑ use this one ↑↑↑


    //
    // Scope data.
    //
    $scope.username = $routeParams.username;
    $scope.librarySlug = $routeParams.librarySlug;
    $scope.keeps = [];
    $scope.library = {};
    $scope.scrollDistance = '100%';
    $scope.loading = true;
    $scope.hasMore = true;
    $scope.page = null; // This is used to decide which page to show (library, permission denied, login)
    $scope.passphrase = $scope.passphrase || {};


    //
    // Scope methods.
    //
    $scope.getNextKeeps = function () {
      if ($scope.loading || !$scope.library || $scope.keeps.length === 0) {
        return;
      }

      $scope.loading = true;
      return libraryService.getKeepsInLibrary($scope.library.id, $scope.keeps.length, authToken).then(function (res) {
        var rawKeeps = res.keeps;

        rawKeeps.forEach(function (rawKeep) {
          var keep = new keepDecoratorService.Keep(rawKeep);
          keep.buildKeep(keep);
          keep.makeKept();

          $scope.keeps.push(keep);
        });

        $scope.hasMore = $scope.keeps.length < $scope.library.numKeeps;
        $scope.loading = false;

        return $scope.keeps;
      });
    };

    $scope.getSubtitle = function () {
      if ($scope.loading || !$scope.library) {
        return 'Loading...';
      }

      // If there are selected keeps, display the number of keeps
      // in the subtitle.
      if (selectedCount > 0) {
        return (selectedCount === 1) ? '1 Keep selected' : selectedCount + ' Keeps selected';
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

    $scope.updateSelectedCount = function (numSelected) {
      selectedCount = numSelected;
    };


    //
    // Watches and listeners.
    //
    $rootScope.$on('keepAdded', function (e, libSlug, keep) {
      if (libSlug === $scope.librarySlug) {
        $scope.keeps.unshift(keep);
      }
    });


    //
    // On LibraryCtrl initialization.
    //

    // librarySummaries has a few of the fields we need to draw the library.
    // Attempt to pre-populate the library object while we wait
    if ($scope.$root.userLoggedIn && libraryService.librarySummaries) {
      var path = '/' + $scope.username + '/' + $scope.librarySlug;
      var lib = _.find(libraryService.librarySummaries, function (elem) {
        return elem.url === path;
      });

      if (lib) {
        $scope.page = 'library';
        util.replaceObjectInPlace($scope.library, lib);
        prePopulated = true;
      }
    }

    // Request for library object also retrieves an initial set of keeps in the library.
    libraryService.getLibraryByUserSlug($scope.username, $scope.librarySlug, authToken).then(function (library) {
      // If library information has already been prepopulated, extend the library object.
      // Otherwise, replace library object completely with the newly fetched object.
      if (prePopulated) {
        _.assign($scope.library, library);
      } else {
        util.replaceObjectInPlace($scope.library, library);
      }

      library.keeps.forEach(function (rawKeep) {
        var keep = new keepDecoratorService.Keep(rawKeep);
        keep.buildKeep(keep);
        keep.makeKept();

        $scope.keeps.push(keep);
      });

      $scope.hasMore = $scope.keeps.length < $scope.library.numKeeps;
      $scope.loading = false;
      $scope.page = 'library';
    }, function onError(resp) {
      if (resp.data && resp.data.error) {
        if (resp.data.error === 'invalid_access' && authToken) {
          $scope.page = 'login';
        } else {
          $scope.page = 'permission_denied';
        }
      }
    });


    $scope.submitPassPhrase = function () {
      console.log('hit', $scope.library.id);
      libraryService.authIntoLibrary($scope.library.id, authToken, $scope.passphrase.value).then(function (result) {
        console.log('success', result);
      })['catch'](function (err) {
        console.log('error', err);
      });
    };
  }
]);
