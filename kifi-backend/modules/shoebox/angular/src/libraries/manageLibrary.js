'use strict';

angular.module('kifi')

.directive('kfManageLibrary', [
  '$location', '$window', '$rootScope', 'friendService', 'libraryService', 'modalService',
  'profileService', 'userService', 'util',
  function ($location, $window, $rootScope, friendService, libraryService, modalService,
    profileService, userService, util) {
    return {
      restrict: 'A',
      require: '^kfModal',
      templateUrl: 'libraries/manageLibrary.tpl.html',
      link: function (scope, element, attrs, kfModalCtrl) {
        //
        // Internal data.
        //
        var nameInput = element.find('.manage-lib-name-input');
        var returnAction = null;
        var submitting = false;


        //
        // Scope data.
        //
        scope.username = profileService.me.username;
        scope.userHasEditedSlug = false;
        scope.emptySlug = true;
        scope.$error = {};
        scope.showFollowers = false;


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

          scope.$error.name = libraryService.getLibraryNameError(scope.library.name, scope.modalData && scope.modalData.library.name);
          if (scope.$error.name) {
            return;
          }

          if (scope.userHasEditedSlug) {
            scope.library.slug = util.generateSlug(scope.library.slug);
          }

          submitting = true;
          console.log('listed: ' + scope.library.listed);

          var saveData = {
            id: scope.library.id,
            name: scope.library.name,
            description: scope.library.description,
            slug: scope.library.slug,
            visibility: scope.library.visibility,
            listed: scope.library.listed
          };
          var promise;
          if (scope.modifyingExistingLibrary && scope.library.id) {
            promise = libraryService.modifyLibrary(saveData);
          } else {
            promise = libraryService.createLibrary(saveData);
          }

          promise.then(function (resp) {
            scope.$error = {};

            submitting = false;

            libraryService.fetchLibrarySummaries(true).then(function () {
              scope.close();

              if (!returnAction) {
                $location.path(resp.data.url);
              } else {
                returnAction();
              }
            });
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
            $rootScope.$emit('librarySummariesChanged');
            scope.close();
            $location.path('/');
          })['catch'](modalService.openGenericErrorModal)['finally'](function () {
            submitting = false;
          });
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
                  member.picUrl = friendService.getPictureUrlForUser(member);
                  member.profileUrl = userService.getProfileUrl(member.username);
                  member.status = setMemberStatus(member);
                });
                scope.memberList.push.apply(scope.memberList, members);
              }
            });
          }
        }

        function setMemberStatus (member) {
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
        if (scope.modalData) {
          scope.library = _.cloneDeep(scope.modalData.library);
          if (scope.modalData.pane === 'members') {
            scope.viewFollowersFirst = true;
            scope.showFollowers = true;
          }
          scope.library.followers.forEach(function (follower) {
            follower.status = 'Following';
          });
          returnAction = scope.modalData.returnAction || null;
          scope.modifyingExistingLibrary = true;
          scope.emptySlug = false;
          scope.modalTitle = scope.library.name;
        } else {
          scope.library = {
            'name': '',
            'description': '',
            'slug': '',

            // By default, the create library form selects the "published" visibility for a new library.
            'visibility': 'published',
            'listed': true
          };
          scope.modalTitle = 'Create a library';
        }

        nameInput.focus();

        scope.inUserProfileBeta = userService.inUserProfileBeta();
      }
    };
  }
]);
