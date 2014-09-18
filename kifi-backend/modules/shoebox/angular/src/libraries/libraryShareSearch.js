'use strict';

angular.module('kifi')

.directive('kfLibraryShareSearch', ['$document', 'friendService', 'keyIndices', 'libraryService', 'socialService', 'util',
  function ($document, friendService, keyIndices, libraryService, socialService, util) {
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
        var searchInput = element.find('.kf-library-share-search-input');
        var show = false;

        
        //
        // Scope data.
        //
        scope.results = [];
        scope.search = {};
        scope.share = {};
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
                  result.isFollowing = isFollowingLibrary(result);
                }
              });

              if (opt_query) {
                resultIndex = 0;
                scope.results[resultIndex].selected = true;
              } else {
                resultIndex = -1;
              }

              if (contacts.length < 5) {
                scope.results.push({ custom: 'email' });
              }
            } else {
              scope.results = [
                { custom: 'email' },
                { custom: 'importGmail', actionable: true}
              ];

              if (opt_query && util.validateEmail(opt_query)) {  // Valid email? Select.
                resultIndex = 0;
                scope.results[resultIndex].selected = true;
                scope.results[resultIndex].actionable = true;
              }
            }
          });
        }

        function shareLibrary(opts) {
          if (scope.share.message) {
            opts.message = scope.share.message;
          }

          // TODO(yiping): implement error path.
          return libraryService.shareLibrary(scope.library.id, opts);
        }

        function isFollowingLibrary(user) {
          // For dev testing.
          // scope.library.followers.push({ id: 'dc6cb121-2a69-47c7-898b-bc2b9356054c' });

          return _.some(scope.library.followers, function (follower) {
            return follower.id === user.id;
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
        scope.onSearchInputFocus = function () {
          // For empty state (when user has not inputted a query), show the contacts
          // that the user has most recently sent messages to.
          if (!scope.search.name) {
            populateDropDown();
          }
        };

        scope.onSearchInputChange = _.debounce(function () {
          populateDropDown(scope.search.name);
        }, 200);

        scope.onResultHover = function (result) {
          clearSelection();
          result.selected = true;
          resultIndex = _.indexOf(scope.results, result);
        };

        scope.onResultUnhover = function (result) {
          result.selected = false;
        };

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

        scope.shareLibraryKifiFriend = function (result) {
          return shareLibrary({
            invites: [{
              type: 'user',
              id: result.id,
              access: 'read_only'
            }]
          }).then(function () {
            result.sent = true;
          });
        };

        scope.shareLibraryExistingEmail = function (result) {
          return shareLibrary({
            invites: [{
              type: 'email',
              id: result.email,
              access: 'read_only'
            }]
          }).then(function () {
            result.sent = true;
          });
        };

        scope.shareLibraryNewEmail = function () {
          if (!util.validateEmail(scope.search.name)) {
            return;
          }

          return shareLibrary({
            invites: [{
              type: 'email',
              id: scope.search.name,
              access: 'read_only'
            }]
          });
        };

        scope.importGmail = function () {
          socialService.importGmail();
        };
      }
    };
  }
]);
