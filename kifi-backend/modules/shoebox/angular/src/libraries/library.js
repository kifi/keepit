'use strict';

angular.module('kifi')

.controller('LibraryCtrl', [
  '$scope', '$rootScope', '$location', '$state', '$stateParams', 'keepDecoratorService', 'libraryService',
  'modalService', 'profileService', 'util', '$window', '$analytics', 'platformService',
  function ($scope, $rootScope, $location, $state, $stateParams, keepDecoratorService, libraryService,
            modalService, profileService, util, $window, $analytics, platformService) {
    //
    // Internal data.
    //
    var selectedCount = 0;
    var prePopulated = false;
    var authToken = $location.search().authToken || '';
    var prevLibrarySearch = false;


    //
    // Internal functions
    //
    function trackPageView(attributes) {
      var url = $analytics.settings.pageTracking.basePath + $location.url();
      var library = $scope.library;
      var trackLibraryAttributes = _.extend(attributes || {},
        libraryService.getCommonTrackingAttributes(library));

      if (profileService.me && profileService.me.id) {
        trackLibraryAttributes.owner = $scope.userIsOwner() ? 'Yes' : 'No';
      }

      // o=lr shorthand for origin=libraryRec
      if ($location.url().indexOf('o=lr') > -1) {
        trackLibraryAttributes.origin = 'libraryRec';
      }

      $analytics.pageTrack(url, trackLibraryAttributes);
    }

    function setTitle(lib) {
      $window.document.title = lib.name + ' by ' + lib.owner.firstName + ' ' + lib.owner.lastName + ' • Kifi' ;
    }


    //
    // Scope data.
    //
    $scope.librarySearch = $state.current.data.librarySearch;
    $scope.username = $stateParams.username;
    $scope.librarySlug = $stateParams.librarySlug;
    $scope.keeps = [];
    $scope.library = {};
    $scope.scrollDistance = '100%';
    $scope.loading = false;
    $scope.hasMore = true;
    $scope.page = null; // This is used to decide which page to show (library, permission denied, login)
    $scope.isMobile = platformService.isSupportedMobilePlatform();
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

        // Preload for non-mobile public pages
        if (!$scope.$root.userLoggedIn && $scope.hasMore && $scope.keeps.length < 30 && !platformService.isSupportedMobilePlatform()) {
          $scope.$evalAsync($scope.getNextKeeps.bind(null, $scope.keeps.length));
        }

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

    $scope.libraryKeepClicked = function (keep, event) {
      var eventAction = event.target.getAttribute('click-action');
      $rootScope.$emit('trackLibraryEvent', 'click', { action: eventAction });
    };


    //
    // Watches and listeners.
    //
    $scope.$watch('library.id', function (id) {
      $rootScope.$broadcast('currentLibraryChanged', $scope.library);

      if (id) {
        libraryService.getRelatedLibraries(id, profileService.me.id).then(function (libraries) {
          $scope.relatedLibraries = libraries;
          trackPageView({ libraryRecCount: libraries.length });
          $rootScope.$broadcast('relatedLibrariesChanged', $scope.library, libraries);
        });
      }
    });

    var deregisterKeepAdded = $rootScope.$on('keepAdded', function (e, libSlug, keeps, library) {
      keeps.forEach(function (keep) {
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

        // add the new keep to the keep card's "my keeps" array
        var existingKeep = _.find($scope.keeps, { url: keep.url });
        if (existingKeep && !_.find($scope.keeps, { id: keep.id })) {
          existingKeep.keeps.push({
            id: keep.id,
            isMine: true,
            libraryId: library.id,
            mine: true,
            visibility: library.visibility
          });
        }
      });
    });
    $scope.$on('$destroy', deregisterKeepAdded);

    var deregisterCurrentLibrary = $rootScope.$on('getCurrentLibrary', function (e, args) {
      args.callback($scope.library);
    });
    $scope.$on('$destroy', deregisterCurrentLibrary);

    var deregisterTrackLibraryEvent = $rootScope.$on('trackLibraryEvent', function (e, eventType, attributes) {
      if (eventType === 'click') {
        if (!$rootScope.userLoggedIn) {
          attributes.type = attributes.type || 'libraryLanding';
          libraryService.trackEvent('visitor_clicked_page', $scope.library, attributes);
        } else {
          attributes.type = attributes.type || 'library';
          libraryService.trackEvent('user_clicked_page', $scope.library, attributes);
        }
      } else if (eventType === 'view') {
        trackPageView(attributes);
      }
    });
    $scope.$on('$destroy', deregisterTrackLibraryEvent);

    var deregisterUpdateLibrarySearch = $rootScope.$on('librarySearchChanged', function (e, librarySearch) {
      prevLibrarySearch = $scope.librarySearch;
      $scope.librarySearch = librarySearch;
      $scope.librarySearchFallingEdge = prevLibrarySearch && !$scope.librarySearch;
    });
    $scope.$on('$destroy', deregisterUpdateLibrarySearch);


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
        $rootScope.$emit('libraryUrl', $scope.library);
        if ($scope.library.kind === 'user_created' && $scope.library.access !== 'none') {
          $rootScope.$emit('lastViewedLib', $scope.library);
        }
        if ($scope.hasMore) {
          $scope.$evalAsync($scope.getNextKeeps.bind(null, library.keeps.length)); // fetch the next page
        }
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

    $scope.submitPassPhrase = function () {
      libraryService.authIntoLibrary($scope.username, $scope.librarySlug, authToken, $scope.passphrase.value.toLowerCase()).then(function () {
        init(true);
      })['catch'](function (err) {
        $scope.$error.name = 'Oops, that didn’t work. Try again? Check the email you received for the correct pass phrase.';
        return err;
      });
    };
  }
]);
