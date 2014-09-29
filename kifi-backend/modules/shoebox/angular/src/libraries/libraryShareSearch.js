'use strict';

angular.module('kifi')

.directive('kfLibraryShareSearch', ['$document', '$timeout', 'friendService', 'keyIndices', 'libraryService', 'socialService', 'util',
  function ($document, $timeout, friendService, keyIndices, libraryService, socialService, util) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        library: '=',
        manageLibInvite: '='
      },
      templateUrl: 'libraries/libraryShareSearch.tpl.html',
      link: function (scope, element/*, attrs*/) {
        //
        // Internal data.
        //
        var resultIndex = -1;
        var shareMenu = element.find('.library-share-menu');
        var searchInput = element.find('.library-share-search-input');
        var show = false;


        //
        // Scope data.
        //
        scope.results = [];
        scope.search = {};
        scope.share = {};
        scope.showSpinner = false;
        scope.email = 'Send to any email';
        scope.emailHelp = 'Type the email address';


        //
        // Internal methods.
        //
        function showMenu() {
          resultIndex = -1;
          clearSelection();
          scope.search.name = '';
          scope.share.message = '';
          $document.on('click', onClick);
          show = true;
          shareMenu.show();

          if (!scope.manageLibInvite) {
            // When we test this conditional, Angular thinks that we're trying to
            // enter an already-in-progress digest loop. To get around this, use
            // $timeout to schedule the following code in a future call stack.
            $timeout(function () {
              searchInput.focus();
              populateDropDown();
            }, 0);
          }
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
              if (!scope.manageLibInvite) {
                hideMenu();
              } else {
                scope.results.length = 0;
              }
            });
          }
        }

        function clearSelection () {
          scope.results.forEach(function (result) {
            result.selected = false;
          });
        }

        function emphasizeMatchedPrefix (text, prefix) {
          if (util.startsWithCaseInsensitive(text, prefix)) {
            return '<b>' + text.substr(0, prefix.length) + '</b>' + text.substr(prefix.length);
          }
          return text;
        }

        function emphasizeMatchedNames (name, prefix) {
          var names = name.split(/\s+/);  // TODO(yiping): is it worth it to use a regex here?

          names.forEach(function (name, index, names) {
            names[index] = emphasizeMatchedPrefix(name, prefix);
          });

          return names.join(' ');
        }

        function populateDropDown(opt_query) {
          // Update the email address and email help text being displayed.
          if (opt_query) {
            scope.email = opt_query;
            scope.emailHelp = 'Keep typing the email address';

            if (util.validateEmail(opt_query)) {
              scope.emailHelp = 'An email address';
            }
          }

          scope.showSpinner = true;
          libraryService.getLibraryShareContacts(opt_query).then(function (contacts) {
            var newResults;

            if (contacts && contacts.length) {
              newResults = _.clone(contacts);

              newResults.forEach(function (result) {
                if (result.id) {
                  result.image = friendService.getPictureUrlForUser(result);
                  result.isFollowing = isFollowingLibrary(result);
                }

                if (opt_query) {
                  if (result.name) {
                    result.name = emphasizeMatchedNames(result.name, opt_query);
                  }
                  if (result.email) {
                    result.email = emphasizeMatchedPrefix(result.email, opt_query);
                  }
                }
              });

              if (opt_query) {
                resultIndex = 0;
                newResults[resultIndex].selected = true;
              } else {
                resultIndex = -1;
              }

              if (contacts.length < 5) {
                newResults.push({ custom: 'email' });
              }

            } else {
              newResults = [
                { custom: 'email' },
                { custom: 'importGmail', actionable: true}
              ];

              if (opt_query && util.validateEmail(opt_query)) {  // Valid email? Select.
                resultIndex = 0;
                newResults[resultIndex].selected = true;
                newResults[resultIndex].actionable = true;
              }
            }

            scope.showSpinner = false;

            // Animate height change on list of contacts.
            var contactList = element.find('.library-share-contact-list');
            var prevContactsHeight = contactList.height();
            var newContactsHeight = 53 * newResults.length + 'px';
            contactList.height(prevContactsHeight).animate({ height: newContactsHeight }, {
              duration: 70,
              start: function () {
                scope.results = newResults;
              }
            });
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
        // Scope methods.
        //
        scope.toggleMenu = function () {
          if (show) {
            hideMenu();
          } else {
            showMenu();
          }
        };

        scope.onSearchInputChange = _.debounce(function () {
          populateDropDown(scope.search.name);
        }, 200);

        scope.onSearchInputFocus = function () {
          populateDropDown(scope.search.name);
        };

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
                var result = scope.results[resultIndex];

                if (result.id) {
                  scope.shareLibraryKifiFriend(result);
                } else if (result.email) {
                  scope.shareLibraryExistingEmail(result);
                } else if (result.custom === 'email') {
                  scope.shareLibraryNewEmail(result);
                } else if (result.custom === 'importGmail') {
                  scope.importGmail();
                }
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

        scope.shareLibraryNewEmail = function (result) {
          if (!util.validateEmail(scope.search.name)) {
            return;
          }

          return shareLibrary({
            invites: [{
              type: 'email',
              id: scope.search.name,
              access: 'read_only'
            }]
          }).then(function () {
            result.sent = true;
          });
        };

        scope.importGmail = function () {
          socialService.importGmail();
        };


        //
        // On link.
        //
        if (scope.manageLibInvite) {
          searchInput.attr('placeholder', 'Type a name or email');
          showMenu();
        }
      }
    };
  }
]);
