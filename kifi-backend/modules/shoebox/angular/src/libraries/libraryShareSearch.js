'use strict';

angular.module('kifi')

.directive('kfLibraryShareSearch', [
  '$document', '$timeout', 'friendService', 'KEY', 'libraryService', 'socialService', 'profileService', 'util',
  function ($document, $timeout, friendService, KEY, libraryService, socialService, profileService, util) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        library: '=',
        manageLibInvite: '=',
        currentPageOrigin: '@',
        btnIconClass: '@',
        close: '&'
      },
      templateUrl: 'libraries/libraryShareSearch.tpl.html',
      link: function (scope, element) {
        //
        // Internal data.
        //
        var resultIndex = -1;
        var shareMenu = element.find('.library-share-menu');
        var searchInput = element.find('.library-share-search-input');
        var contactList = element.find('.library-share-contact-list');
        var show = false;
        var justScrolled = false;
        var resetJustScrolledTimeout = null;


        //
        // Scope data.
        //
        scope.isOwn = scope.library.owner.id === profileService.me.id;
        scope.results = [];
        scope.search = {};
        scope.share = {};
        scope.showSpinner = false;
        scope.email = 'Send to any email';
        scope.emailHelp = 'Type the email address';


        //
        // Internal methods.
        //
        function init() {
          if (scope.manageLibInvite) {
            searchInput.attr('placeholder', 'Type a name or email');
            showMenu();
          }
        }

        function showMenu() {
          resultIndex = -1;
          clearSelection();
          scope.search.name = '';
          scope.share.message = '';
          scope.email = 'Send to any email';
          $document.on('click', onClick);
          show = true;
          shareMenu.show();
          contactList.scrollTop(0);

          trackShareEvent('user_clicked_page', { action: 'clickedShareEmail' });

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
          $timeout.cancel(resetJustScrolledTimeout);
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
          prefix = prefix || '';
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
          libraryService.getLibraryShareContacts(scope.library.id, opt_query).then(function (contacts) {
            var newResults;

            if (contacts && contacts.length) {

              if (!opt_query) {
                // remove any contacts who are already following (anybody who has an access, but no lastInvitedAt field)
                // only if there's no query
                _.remove(contacts, function(c) { return c.membership && !c.lastInvitedAt; });
              }

              // Clone deeply; otherwise, the data augmentation we do on individual contacts
              // will be cached as part of the contacts cached by Clutch.
              newResults = _.clone(contacts, true);

              newResults.forEach(function (result) {
                if (result.id) {
                  result.isInvited = !!result.lastInvitedAt;
                  result.isFollowing = !!result.membership && !result.isInvited;
                  result.name = (result.firstName || '') + (result.lastName ? ' ' + result.lastName : '');
                }

                if (result.name) {
                  result.name = emphasizeMatchedNames(result.name, opt_query);
                }

                if (result.email) {
                  result.emailFormatted = emphasizeMatchedPrefix(result.email, opt_query);
                }
              });

              if (opt_query) {
                resultIndex = 0;
                if (newResults[resultIndex]) {
                  newResults[resultIndex].selected = true;
                }
              } else {
                resultIndex = -1;
              }

              if (contacts.length < 5) {
                newResults.push({ custom: 'email', hideButton: true });
              }

            } else {
              newResults = [
                { custom: 'email', hideButton: true },
                { custom: 'importGmail', actionable: true}
              ];

              if (opt_query && util.validateEmail(opt_query)) {
                // Valid email? Select and show button.
                resultIndex = 0;
                newResults[resultIndex].selected = true;
                newResults[resultIndex].hideButton = false;
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


        // TODO(yiping): make a directive for displaying a list of items where up and down
        // keys work to select items and where the list automatically scrolls on up and down
        // key presses to hidden items.
        /**
         * If a user uses up-and-down arrows to select a contact that is not visible,
         * scroll the list of contacts so that the selected contacts is visible.
         *
         * @param {number} selectedIndex - the index of the selected contact in the list of contacts.
         */
        function adjustScroll(selectedIndex) {

          /**
           * After we finish scrolling, we set a flag that a scroll has just happened so that
           * a mouseenter event on a library item that was triggered as a result of the scroll
           * would not result in that contact being selected. After a short amount of time,
           * set the flag to false so that mouseenter can function as normal.
           */
          function setJustScrolled() {
            justScrolled = true;
            resetJustScrolledTimeout = $timeout(function () {
              justScrolled = false;
            }, 200);
          }

          // Each contact is 53px high, and we fit in 5 contacts within the
          // visible area. For a contact to be visible, it should be entirely within the
          // visible area (this means its top offset should be at least one contact height from
          // the visible bottom).

          var contactHeight = 53;
          var maxNumContactsShown = 5;

          var selectedContactTop = selectedIndex * contactHeight;
          var visibleTop = contactList.scrollTop();
          var visibleBottom = visibleTop + (maxNumContactsShown * contactHeight);

          if (selectedContactTop < visibleTop) {
            contactList.scrollTop(selectedContactTop);
            setJustScrolled();
          } else if (selectedContactTop > (visibleBottom - contactHeight)) {
            contactList.scrollTop(selectedContactTop - ((maxNumContactsShown - 1) * contactHeight));
            setJustScrolled();
          }
        }

        function trackShareEvent(eventName, attr) {
          var type = scope.currentPageOrigin === 'recommendationsPage' ? 'recommendations' : 'library';
          var attributes = _.extend({ type: type }, attr || {});
          libraryService.trackEvent(eventName, scope.library, attributes);
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
            case KEY.UP:
              $event.preventDefault();  // Otherwise browser will move cursor to start of input.
              clearSelection();
              resultIndex = getNextIndex(resultIndex, -1);
              scope.results[resultIndex].selected = true;
              adjustScroll(resultIndex);
              break;
            case KEY.DOWN:
              $event.preventDefault();  // Otherwise browser will move cursor to end of input.
              clearSelection();
              resultIndex = getNextIndex(resultIndex, 1);
              scope.results[resultIndex].selected = true;
              adjustScroll(resultIndex);
              break;
            case KEY.ENTER:
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
            case KEY.ESC:
              hideMenu();
              break;
          }
        };

        scope.shareLibraryKifiFriend = function (result) {
          trackShareEvent('user_clicked_page', { action: 'clickedContact', subAction: 'kifiFriend' });

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
          trackShareEvent('user_clicked_page', { action: 'clickedContact', subAction: 'existingEmail' });

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

          trackShareEvent('user_clicked_page', { action: 'clickedContact', subAction: 'newEmail' });

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


        init();
      }
    };
  }
]);
