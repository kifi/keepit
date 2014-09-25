'use strict';

angular.module('kifi')

.controller('LibraryCtrl', ['$scope', '$rootScope', '$location', '$routeParams', 'keepDecoratorService', 'libraryService', 'modalService', 'profileService', 'util',
  function ($scope, $rootScope, $location, $routeParams, keepDecoratorService, libraryService, modalService, profileService, util) {
    //
    // Internal data.
    //
    var username = $routeParams.username;
    var librarySlug = $routeParams.librarySlug;
    var selectedCount = 0;


    //
    // Scope data.
    //
    $scope.keeps = [];
    $scope.library = {};
    $scope.scrollDistance = '100%';
    $scope.loading = true;
    $scope.hasMore = true;


    //
    // Scope methods.
    //
    $scope.getNextKeeps = function () {
      if ($scope.loading || !$scope.library || $scope.keeps.length === 0) {
        return;
      }

      $scope.loading = true;
      return libraryService.getKeepsInLibrary($scope.library.id, $scope.keeps.length).then(function (res) {
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

    $scope.manageLibrary = function () {
      modalService.open({
        template: 'libraries/manageLibraryModal.tpl.html',
        modalData: {
          library: $scope.library,
          returnAction: function () {
            libraryService.getLibraryById($scope.library.id, true).then(function (data) {
              libraryService.getLibraryByUserSlug(username, data.library.slug, true);
              if (data.library.slug !== librarySlug) {
                $location.path('/' + username + '/' + data.library.slug);
              }
            });
          }
        }
      });
    };

    $scope.canBeShared = function (library) {
      // Only user created (i.e. not Main or Secret) libraries can be shared.
      // Of the user created libraries, public libraries can be shared by any Kifi user;
      // discoverable/secret libraries can be shared only by the library owner.
      return library.kind === 'user_created' &&
             (library.visibility === 'published' ||
              library.ownerId === profileService.me.id);
    };

    // This needs to be determined server side in the library response. For now, doing it client side.
    $scope.canBeFollowed = function (library) {
      var alreadyFollowing = _.find(library.followers, function (elem) {
        return elem.id === profileService.me.id;
      });
      return !alreadyFollowing && library.ownerId !== profileService.me.id;
    };

    $scope.followLibrary = function (libraryId) {
      libraryService.joinLibrary(libraryId);
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
      if (libSlug === librarySlug) {
        $scope.keeps.unshift(keep);
      }
    });

    $rootScope.$on('changedLibrary', function () {
      // TODO(yiping): figure out why this is slow to update.
      libraryService.getLibraryByUserSlug(username, librarySlug).then(function (library) {
        util.replaceObjectInPlace($scope.library, library);
      });
    });


    //
    // On LibraryCtrl initialization.
    //

    // librarySummaries has a few of the fields we need to draw the library.
    // Attempt to pre-populate the library object while we wait
    if (libraryService.librarySummaries) {
      var path = '/' + username + '/' + librarySlug;
      var lib = _.find(libraryService.librarySummaries, function (elem) {
        return elem.url === path;
      });

      if (lib) {
        util.replaceObjectInPlace($scope.library, lib);
      }
    }

    // Request for library object also retrieves an initial set of keeps in the library.
    libraryService.getLibraryByUserSlug(username, librarySlug).then(function (library) {
      util.replaceObjectInPlace($scope.library, library);

      library.keeps.forEach(function (rawKeep) {
        var keep = new keepDecoratorService.Keep(rawKeep);
        keep.buildKeep(keep);
        keep.makeKept();

        $scope.keeps.push(keep);
      });

      $scope.hasMore = $scope.keeps.length < $scope.library.numKeeps;
      $scope.loading = false;
    });
  }
]);
