'use strict';

angular.module('kifi')

.controller('LibraryCtrl', [
  '$scope',
  '$rootScope',
  '$location',
  '$routeParams',
  'keepDecoratorService',
  'libraryService',
  'profileService',
  'util', 
  function ($scope, $rootScope, $location, $routeParams,
            keepDecoratorService, libraryService, profileService, util) {
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
      libraryService.libraryState = {
        library: $scope.library,
        returnAction: function () {
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

    $scope.canBeShared = function (library) {
      // Only user created (i.e. not Main or Secret) libraries can be shared.
      // Of the user created libraries, public libraries can be shared by any Kifi user;
      // discoverable/secret libraries can be shared only by the library owner.
      return library.kind === 'user_created' && 
             (library.visibility === 'published' ||
              library.ownerId === profileService.me.id);
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
])

.directive('kfLibraryShareSearch', ['$document', 'friendService', 'keyIndices', 'libraryService',
  function ($document, friendService, keyIndices, libraryService) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        library: '='
      },
      templateUrl: 'libraries/libraryShareSearch.tpl.html',
      link: function (scope, element/*, attrs*/) {
        //
        // Internal data.
        //
        var resultIndex = -1;
        var shareButton = element.find('.kf-library-share-btn');
        var shareMenu = element.find('.kf-library-share-menu');
        var show = false;


        //
        // Scope data.
        //
        scope.results = [];
        scope.search = {};
        scope.showDropdown = false;


        //
        // Internal methods.
        //
        function showMenu() {
          shareMenu.show();
          show = true;
          $document.on('click', onClick);
        }

        function hideMenu() {
          shareMenu.hide();
          show = false;
          $document.off('click', onClick);
        }

        function showDropdown() {
          scope.showDropdown = true;
          resultIndex = -1;
          scope.search.name = '';
          clearSelection();
        }

        function hideDropdown() {
          scope.showDropdown = false;
        }

        function onClick(e) {
          // Clicking outside the menu will close the menu.
          if (!element.find(e.target)[0]) {
            scope.$apply(function () {
              hideMenu();
            });
          }
        }

        function clearSelection () {
          scope.results.forEach(function (result) {
            result.selected = false;
          });
        }

        function populateDropDown(opt_query) {
          libraryService.getLibraryShareContacts(opt_query).then(function (contacts) {
            if (contacts && contacts.length) {
              scope.results = contacts;

              scope.results.forEach(function (result) {
                if (result.id) {
                  result.image = friendService.getPictureUrlForUser(result);
                }
              });

              if (scope.results.length > 0) {
                showDropdown();
              } else {
                hideDropdown();
              }
            } else {
              // TODO(yiping): show backfill cards when length is less than 5.
              scope.results = [];
            }
          });
        }


        //
        // DOM event listeners.
        //
        shareButton.on('click', function () {
          if (show) {
            hideMenu();
          } else {
            showMenu();
          }
        });


        //
        // Scope methods.
        //
        scope.onInputFocus = function () {
          // For empty state (when user has not inputted a query), show the contacts
          // that the user has most recently sent messages to.
          if (!scope.search.name) {
            populateDropDown();
          }
        };

        scope.change = _.debounce(function () {
          populateDropDown(scope.search.name);
        }, 200);

        scope.processKeyEvent = function ($event) {
          function getNextIndex(index, direction) {
            var nextIndex = index + direction;
            return (nextIndex < 0 || nextIndex > scope.results.length - 1) ? index : nextIndex;
          }

          switch ($event.keyCode) {
            case keyIndices.KEY_UP:
              clearSelection();
              resultIndex = getNextIndex(resultIndex, -1);
              scope.results[resultIndex].selected = true;
              break;
            case keyIndices.KEY_DOWN:
              clearSelection();
              resultIndex = getNextIndex(resultIndex, 1);
              scope.results[resultIndex].selected = true;
              break;
            case keyIndices.KEY_ENTER:
              clearSelection();
              scope.shareLibrary(scope.results[resultIndex]);

              // After sharing, reset index.
              resultIndex = -1;
              break;
            case keyIndices.KEY_ESC:
              hideDropdown();
              break;
          }
        };

        scope.shareLibrary = function (result) {
          // For now, we are only supporting inviting one person at a time.
          var invitees = [
            {
              type: result.id ? 'user' : 'email',
              id: result.id ? result.id : result.email,
              access: 'read_only'  // Right now, we're only supporting read-only access.
            }
          ];

          // TODO(yiping): implement error path.
          libraryService.shareLibrary(scope.library.id, {'invites': invitees}).then(function () {
            result.sent = true;
          });
        };

        scope.onResultHover = function (result) {
          clearSelection();
          result.selected = true;
          resultIndex = _.indexOf(scope.results, result);
        };
      }
    };
  }
]);
