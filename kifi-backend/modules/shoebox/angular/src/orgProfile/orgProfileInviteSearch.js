'use strict';

angular.module('kifi')

.directive('kfOrgInviteSearch', [
  'libraryService', 'profileService', 'orgProfileService', 'socialService', '$timeout', 'util', 'KEY', 'net',
  function (libraryService, profileService, orgProfileService, socialService, $timeout, util, KEY, net) {
    return {
      restrict: 'A',
      require: '^kfModal',
      templateUrl: 'orgProfile/orgProfileInviteSearch.tpl.html',
      link: function (scope, element, attrs, kfModalCtrl) {

        //
        // Internal data.
        //
        var resultIndex = -1;
        var searchInput = element.find('.opis-search-input');
        var contactList = element.find('.opis-contact-list');
        var inviteAccess = {
          // collaborate: 'read_write',
          // follow: 'read_only'
          own: 'admin',
          join: 'member'
        };

        //
        // Scope data.
        //
        scope.results = [];
        scope.search = {};
        scope.share = {};
        scope.showSpinner = false;
        scope.query = '';
        scope.queryIsValidEmail = true;
        scope.inviter = profileService.me;
        scope.organization = scope.modalData.organization;
        scope.inviteType = scope.modalData.inviteType || 'join';
        scope.currentPageOrigin = scope.modalData.currentPageOrigin;

        function shareLibrary(opts) {
          if (scope.share.message) {
            opts.message = scope.share.message;
          }
          var cb = scope.modalData.returnAction;
          return orgProfileService.sendOrgMemberInvite(scope.organization.id, opts).then(cb);
        }

        // function trackShareEvent(eventName, attr) {
        //   var type = scope.currentPageOrigin === 'recommendationsPage' ? 'recommendations' : 'library';
        //   var attributes = _.extend({ type: type }, attr || {});
        //   libraryService.trackEvent(eventName, scope.library, attributes);
        // }

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
            scope.query = opt_query;
            scope.queryIsValidEmail = false;

            if (util.validateEmail(opt_query)) {
              scope.queryIsValidEmail = true;
            }
          }

          scope.showSpinner = true;

          function getFirstLibrary(libraryData) {
            var libraries = libraryData.libraries;
            return libraries && libraries[0];
          }

          function getSearchContacts(library) {
            return library && libraryService.getLibraryShareContacts(library.id, opt_query);
          }

          function updateContacts(contacts) {
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
                  result.isFollowing = (result.membership === 'read_only') && !result.isInvited;
                  result.isCollaborating = (result.membership === 'read_write' || result.membership === 'admin') && !result.isInvited;
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
            var prevContactsHeight = contactList.height();
            var newContactsHeight = 53 * newResults.length + 'px';

            var animateProperties = {
              height: newContactsHeight
            };
            var animateArguments = {
              duration: 70,
              start: function () {
                scope.results = newResults;
              }
            };

            contactList
              .height(prevContactsHeight)
              .animate(animateProperties, animateArguments);
          }

          // Do the magic to get the contacts given the organization id.
          orgProfileService
            .getOrgLibraries(scope.organization.id)
            .then(getFirstLibrary)
            .then(getSearchContacts)
            .then(updateContacts);
        }

        //
        // Scope functions
        //

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
              break;
            case KEY.DOWN:
              $event.preventDefault();  // Otherwise browser will move cursor to end of input.
              clearSelection();
              resultIndex = getNextIndex(resultIndex, 1);
              scope.results[resultIndex].selected = true;
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
              break;
          }
        };

        scope.close = function () {
          kfModalCtrl.close();
        };

        function  shareLibraryKifiFriend(result) {
          //trackShareEvent('user_clicked_page', { action: 'clickedContact', subAction: 'kifiFriend' });

          return shareLibrary({
            invites: [{
              type: 'user',
              id: result.id,
              role: inviteAccess[scope.inviteType]
            }]
          }).then(function () {
            result.sent = true;
          });
        }

        function promoteToCollaborator(result) {
          return net.updateLibraryMembership(scope.library.id, result.id, {access: 'read_write'}).then(function () {
            result.isCollaborating = true;
          });
        }

        scope.inviteOrPromote = function (result) {
          if (result.isFollowing) {
            return promoteToCollaborator(result);
          } else {
            return shareLibraryKifiFriend(result);
          }
        };

        scope.shareLibraryExistingEmail = function (result) {
          //trackShareEvent('user_clicked_page', { action: 'clickedContact', subAction: 'existingEmail' });

          return shareLibrary({
            invites: [{
              type: 'email',
              email: result.email,
              role: inviteAccess[scope.inviteType]
            }]
          }).then(function () {
            result.sent = true;
          });
        };

        scope.shareLibraryNewEmail = function (result) {
          if (!util.validateEmail(scope.search.name)) {
            return;
          }

          //trackShareEvent('user_clicked_page', { action: 'clickedContact', subAction: 'newEmail' });

          return shareLibrary({
            invites: [{
              type: 'email',
              email: scope.search.name,
              role: inviteAccess[scope.inviteType]
            }]
          }).then(function () {
            result.sent = true;
          });
        };

        scope.importGmail = function () {
          socialService.importGmail();
        };

        scope.toggleSelector = function () {
          if (scope.inviteType === 'own') {
            scope.inviteType = 'join';
          } else {
            scope.inviteType = 'own';
          }
        };

        scope.isOwnerInvite = function () {
          return scope.inviteType === 'own';
        };

        scope.isMemberInvite = function () {
          return scope.inviteType === 'join';
        };

        //
        // Initialize.
        //

        $timeout(function () {
          searchInput.focus();
          populateDropDown();
        }, 0);
      }
    };
  }
]);
