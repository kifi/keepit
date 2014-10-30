'use strict';

angular.module('kifi')

.controller('LibraryCtrl', [
  '$scope', '$rootScope', '$location', '$routeParams', 'keepDecoratorService', 'libraryService',
  'modalService', 'profileService', 'util', '$window',
  function ($scope, $rootScope, $location, $routeParams, keepDecoratorService, libraryService,
            modalService, profileService, util, $window) {
    //
    // Internal data.
    //
    var selectedCount = 0;
    var prePopulated = false;
    var authToken = $location.search().authToken || $location.search().authCode || $location.search().accessToken || '';
    //                   ↑↑↑ use this one ↑↑↑


    //
    // Scope data.
    //
    $scope.username = $routeParams.username;
    $scope.librarySlug = $routeParams.librarySlug;
    $scope.keeps = [];
    $scope.library = {};
    $scope.scrollDistance = '100%';
    $scope.loading = false;
    $scope.hasMore = true;
    $scope.page = null; // This is used to decide which page to show (library, permission denied, login)
    $scope.passphrase = $scope.passphrase || {};
    $scope.$error = $scope.$error || {};

    $scope.userIsOwner = function () {
      return $scope.library && $scope.library.owner.id === profileService.me.id;
    };

    //
    // Scope methods.
    //
    $scope.getNextKeeps = function (offset) {
      if ($scope.loading || !$scope.library || $scope.keeps.length === 0) {
        return;
      }
      $scope.loading = true;
      return libraryService.getKeepsInLibrary($scope.library.id, offset, authToken).then(function (res) {
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
    var deregisterKeepAdded = $rootScope.$on('keepAdded', function (e, libSlug, keeps) {
      _.each(keeps, function (keep) {
        // checks if the keep was added to the secret library from main or
        // vice-versa.  If so, it removes the keep from the current library
        if ((libSlug === 'secret' && $scope.librarySlug === 'main') ||
            (libSlug === 'main' && $scope.librarySlug === 'secret')) {
          var idx = _.findIndex($scope.keeps, { url: keep.url });
          if (idx > -1) {
            $scope.keeps.splice(idx, 1);
          }
        } else if (libSlug === $scope.librarySlug) {
          $scope.keeps.unshift(keep);
        }
      });
    });
    $scope.$on('$destroy', deregisterKeepAdded);

    var deregisterCurrentLibrary = $rootScope.$on('getCurrentLibrary', function (e, args) {
      args.callback($scope.library);
    });
    $scope.$on('$destroy', deregisterCurrentLibrary);

    var setTitle = function (lib) {
      $window.document.title = lib.name + ' by ' + lib.owner.firstName + ' ' + lib.owner.lastName + ' • Kifi' ;
    };


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
        setTitle(lib);
        util.replaceObjectInPlace($scope.library, lib);
        prePopulated = true;
      }
    }


    var init = function (invalidateCache) {
      if ($scope.loading) {
        return;
      }
      $scope.loading = true;

      // Request for library object also retrieves an initial set of keeps in the library.
      libraryService.getLibraryByUserSlug($scope.username, $scope.librarySlug, authToken, invalidateCache || false).then(function (library) {
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
        setTitle(library);
      }, function onError(resp) {
        if (resp.data && resp.data.error) {
          $scope.loading = false;
          $scope.page = 'permission_denied';
          if (resp.data.error) {
            if (resp.data.error === 'no_library_found') {
              $scope.page = 'not_found';
            } else if (authToken) {
              $scope.page = 'login';
            }
          }
        }
      });
    };

    $scope.callAddKeep = function () {
      $rootScope.$emit('triggerAddKeep', $scope.library);
    };
    $scope.callImportBookmarks = function () {
      $rootScope.$emit('showGlobalModal', 'importBookmarks');
    };
    $scope.callImportBookmarkFile = function () {
      $rootScope.$emit('showGlobalModal', 'importBookmarkFile');
    };
    $scope.callTriggerInstall = function () {
      $rootScope.$emit('triggerExtensionInstall');
    };

    var deregisterLogin = $rootScope.$on('userLoggedInStateChange', init.bind(this, true));
    $scope.$on('$destroy', deregisterLogin);

    init(true);
    $rootScope.$emit('libraryUrl', $scope.library);

    $scope.submitPassPhrase = function () {
      libraryService.authIntoLibrary($scope.username, $scope.librarySlug, authToken, $scope.passphrase.value.toLowerCase()).then(function () {
        init(true);
      })['catch'](function (err) {
        $scope.$error.name = 'Oops, that didn\'t work. Try again? Check the email you recieved for the correct pass phrase.';
        return err;
      });
    };
  }
]);
