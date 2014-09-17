'use strict';

angular.module('kifi')

.directive('kfLibraryShareSearch', [
  '$document',
  'friendService',
  'keyIndices',
  'libraryService',
  'util',
  function ($document, friendService, keyIndices, libraryService, util) {
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
        var searchInput = element.find('input');
        var show = false;

        
        //
        // Scope data.
        //
        scope.results = [];
        scope.search = {};
        scope.email = 'Send to any email';
        scope.emailHelp = 'Type the email address';


        //
        // Internal methods.
        //
        function showMenu() {
          resultIndex = -1;
          clearSelection();
          scope.search.name = '';
          $document.on('click', onClick);
          show = true;
          shareMenu.show();
          searchInput.focus();
        }

        function hideMenu() {
          $document.off('click', onClick);
          show = false;
          shareMenu.hide();
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
          // Update the email address and email help text being displayed.
          if (opt_query) {
            scope.email = opt_query;
            scope.emailHelp = 'Keep typing the email address';
          }

          libraryService.getLibraryShareContacts(opt_query).then(function (contacts) {
            if (contacts && contacts.length) {
              scope.results = _.clone(contacts);

              scope.results.forEach(function (result) {
                if (result.id) {
                  result.image = friendService.getPictureUrlForUser(result);
                }
              });

              if (opt_query) {
                resultIndex = 0;
                scope.results[resultIndex].selected = true;
              } else {
                resultIndex = -1;
              }

              if (contacts.length < 5) {
                scope.results.push({
                  emailHelp: true
                });
              }
            } else {
              scope.results = [];

              scope.results.push({
                emailHelp: true
              });

              if (opt_query && util.validateEmail(opt_query)) {  // Valid email? Select.
                resultIndex = 0;
                scope.results[resultIndex].selected = true;
              }
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
              $event.preventDefault();  // Otherwise browser will move cursor to start of input.
              clearSelection();
              resultIndex = getNextIndex(resultIndex, -1);
              scope.results[resultIndex].selected = true;
              break;
            case keyIndices.KEY_DOWN:
              $event.preventDefault();  // Otherwise browser will move cursor to end of input.
              clearSelection();
              resultIndex = getNextIndex(resultIndex, 1);
              scope.results[resultIndex].selected = true;
              break;
            case keyIndices.KEY_ENTER:
              clearSelection();
              if (resultIndex !== -1) {
                scope.shareLibrary(scope.results[resultIndex]);
              }

              // After sharing, reset index.
              resultIndex = -1;
              break;
            case keyIndices.KEY_ESC:
              hideMenu();
              break;
          }
        };

        scope.shareLibrary = function (result) {
          // For now, we are only supporting inviting one person at a time.
          var invitees = [
            {
              type: result.id ? 'user' : 'email',
              id: result.id ? result.id : (result.email ? result.email : scope.search.name),
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
