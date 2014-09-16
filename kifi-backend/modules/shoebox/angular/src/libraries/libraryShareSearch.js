'use strict';

angular.module('kifi')

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
