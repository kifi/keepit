'use strict';

angular.module('kifi')

.directive('kfManageLibrary', [
  '$location', '$state', 'friendService', 'libraryService', 'modalService',
  'profileService', 'util',
  function ($location, $state, friendService, libraryService, modalService,
    profileService, util) {
    return {
      restrict: 'A',
      require: '^kfModal',
      templateUrl: 'libraries/manageLibrary.tpl.html',
      link: function (scope, element, attrs, kfModalCtrl) {
        //
        // Internal data.
        //
        var returnAction = null;
        var submitting = false;
        var colorNames = {
          '#447ab7': 'blue',
          '#5ab7e7': 'sky_blue',
          '#4fc49e': 'green',
          '#f99457': 'orange',
          '#dd5c60': 'red',
          '#c16c9e': 'magenta',
          '#9166ac': 'purple'
        };

        //
        // Scope data.
        //
        scope.username = profileService.me.username;
        scope.userHasEditedSlug = false;
        scope.emptySlug = true;
        scope.$error = {};
        scope.showFollowers = false;
        scope.colors = ['#447ab7','#5ab7e7','#4fc49e','#f99457','#dd5c60','#c16c9e','#9166ac'];
        scope.currentPageOrigin = '';
        scope.onCollabExperiment = (profileService.me.experiments || []).indexOf('collaborative') > -1;

        //
        // Scope methods.
        //
        scope.close = function () {
          kfModalCtrl.close();
        };

        scope.editSlug = function () {
          scope.emptySlug = !scope.library.slug;
          scope.userHasEditedSlug = true;
        };

        scope.saveLibrary = function () {
          if (submitting) {
            return;
          }

          scope.$error.name = libraryService.getLibraryNameError(
            scope.library.name,
            scope.modalData && scope.modalData.library && scope.modalData.library.name);
          if (scope.$error.name) {
            return;
          }

          if (scope.userHasEditedSlug) {
            scope.library.slug = util.generateSlug(scope.library.slug);
          }

          submitting = true;

          libraryService[scope.modifyingExistingLibrary && scope.library.id ? 'modifyLibrary' : 'createLibrary']({
            id: scope.library.id,
            name: scope.library.name,
            description: scope.library.description,
            slug: scope.library.slug,
            visibility: scope.library.visibility,
            listed: scope.library.membership.listed,
            color: colorNames[scope.library.color]
          }, true).then(function (resp) {
            libraryService.fetchLibraryInfos(true);

            var newLibrary = resp.data.library;
            newLibrary.listed = resp.data.listed || (resp.data.library.membership && resp.data.library.membership.listed);

            scope.$error = {};
            submitting = false;
            scope.close();

            if (!returnAction) {
              $location.url(newLibrary.url);
            } else {
              returnAction(newLibrary);
            }
          })['catch'](function (err) {
            submitting = false;

            var error = err.data && err.data.error;
            switch (error) {
              case 'invalid_name':
                scope.$error.general = 'The name you picked isn’t valid. Try using only letters and numbers.';
                break;
              case 'invalid_slug':
                scope.$error.general = 'The URL you picked isn’t valid. Try using only letters and numbers.';
                break;
              case 'library_name_exists':
                scope.$error.general = 'You already have a library with this name. Pick another.';
                break;
              case 'library_slug_exists':
                scope.$error.general = 'You already have a library with the same URL. Pick another.';
                break;
              default:
                scope.$error.general = 'Hmm, something went wrong. Try again later?';
                break;
            }
          });
        };

        scope.openDeleteLibraryModal = function () {
          modalService.open({
            template: 'libraries/deleteLibraryConfirmModal.tpl.html',
            scope: scope
          });
        };

        scope.deleteLibrary = function () {
          if (submitting) {
            return;
          }

          submitting = true;
          libraryService.deleteLibrary(scope.library.id).then(function () {
            // If we were on the deleted library's page, return to the homepage.
            if ($state.is('library.keeps') &&
                ($state.href('library.keeps') === scope.library.url)) {
              $location.url('/');
            }
          })['catch'](modalService.openGenericErrorModal)['finally'](function () {
            submitting = false;
          });

          scope.close();
        };

        scope.showFollowersPanel = function () {
          scope.showFollowers = true;
        };

        scope.hideFollowersPanel = function () {
          scope.viewFollowersFirst = false;
          scope.showFollowers = false;
        };

        //
        // Smart Scroll
        //
        scope.moreMembers = true;
        scope.memberList = [];
        scope.memberScrollDistance = '100%';

        scope.isMemberScrollDisabled = function () {
          return !(scope.moreMembers);
        };
        scope.memberScrollNext = function () {
          pageMembers();
        };

        var pageSize = 10;
        scope.offset = 0;
        var loading = false;
        function pageMembers() {
          if (loading) { return; }
          if (scope.library.id) {
            loading = true;
            libraryService.getMoreMembers(scope.library.id, pageSize, scope.offset).then(function (resp) {
              var members = resp.members;
              loading = false;
              if (members.length === 0) {
                scope.moreMembers = false;
              } else {
                scope.moreMembers = true;
                scope.offset += 1;
                members.forEach(function (member) {
                  member.status = setMemberStatus(member);
                });
                scope.memberList.push.apply(scope.memberList, members);
              }
            });
          }
        }

        function setMemberStatus(member) {
          if (member.lastInvitedAt) {
            return 'Invitation Pending';
          } else if (member.membership === 'read_only') {
            return 'Following';
          } else {
            return 'Collaborating';
          }
        }

        //
        // Watches and listeners.
        //
        scope.$watch('library.name', function (newVal, oldVal) {
          if (newVal !== oldVal) {
            // Clear the error popover when the user changes the name field.
            scope.$error.name = null;

            // Update the slug to reflect the library's name only if we're not modifying an
            // existing library and the user has not edited the slug yet.
            if (scope.library.name && !scope.userHasEditedSlug && !scope.modifyingExistingLibrary) {
              scope.library.slug = util.generateSlug(newVal);
              scope.emptySlug = false;
            }
          }
        });


        //
        // On link.
        //
        if (scope.modalData && scope.modalData.library) {
          scope.library = _.cloneDeep(scope.modalData.library);
          if (scope.modalData.pane === 'members') {
            scope.viewFollowersFirst = true;
            scope.showFollowers = true;
          }
          scope.library.followers.forEach(function (follower) {
            follower.status = 'Following';
          });
          scope.modifyingExistingLibrary = true;
          scope.emptySlug = false;
          scope.modalTitle = scope.library.name;
        } else {
          scope.library = {
            'name': '',
            'description': '',
            'slug': '',
            'visibility': 'published'
          };
          scope.library.membership = {
            access: 'owner',
            listed: true,
            subscribed: false
          };
          scope.modalTitle = 'Create a library';
        }
        returnAction = scope.modalData && scope.modalData.returnAction;
        scope.currentPageOrigin = scope.modalData && scope.modalData.currentPageOrigin;

        element.find('.manage-lib-name-input').focus();
      }
    };
  }
]);
