'use strict';

angular.module('kifi')

.directive('kfManageLibrary', [
  '$location', '$window', '$rootScope', 'friendService', 'libraryService', 'modalService', 'profileService', 'util',
  function ($location, $window, $rootScope, friendService, libraryService, modalService, profileService, util) {
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
        scope.$error = {};
        scope.showFollowers = false;


        //
        // Internal methods.
        //
        function getLibraryNameError(name) {
          function hasInvalidCharacters (myString, invalidChars) {
            return _.some(invalidChars, function (invalidChar) {
              return myString.indexOf(invalidChar) !== -1;
            });
          }

          if (!name.length) {
            return 'Please enter a name for your library';
          } else if (scope.library.name.length < 3) {
            return 'Please try a longer name';
          } else if (scope.library.name.length > 50) {
            return 'Please try a shorter name';
          } else if (hasInvalidCharacters(scope.library.name, ['/', '\\', '"', '\''])) {
            return 'Please no slashes or quotes in your library name';
          }

          return null;
        }


        //
        // Scope methods.
        //
        scope.close = function () {
          kfModalCtrl.close();
        };

        scope.editSlug = function () {
          scope.userHasEditedSlug = !!scope.library.slug;
          scope.library.slug = util.generateSlug(scope.library.slug);
        };

        scope.saveLibrary = function () {
          if (submitting) {
            return;
          }

          scope.$error.name = getLibraryNameError(scope.library.name);
          if (scope.$error.name) {
            return;
          }

          submitting = true;
          var promise;
          if (scope.modifyingExistingLibrary && scope.library.id) {
            promise = libraryService.modifyLibrary(scope.library);
          } else {
            promise = libraryService.createLibrary(scope.library);
          }

          promise.then(function (resp) {
            scope.$error = {};

            submitting = false;

            libraryService.fetchLibrarySummaries(true).then(function () {
              $rootScope.$emit('librarySummariesChanged');
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
              case 'library name already exists for user':  // deprecated
              case 'library_name_exists':
                scope.$error.general = 'You already have a library with this name. Pick another.';
                break;
              case 'invalid library name':  // deprecated
              case 'invalid_name':
                scope.$error.general = 'You already have a library with this name. Pick another.';
                break;
              case 'library_slug_exists':
                scope.$error.general = 'You already have a library with the same URL. Pick another.';
                break;
              case 'invalid library slug':  // deprecated
              case 'invalid_slug':
                scope.$error.general = 'The URL you picked isn\'t valid. Try using only letters and numbers.';
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
            submitting = false;
            $rootScope.$emit('librarySummariesChanged');
            scope.close();
            $location.path('/');
          });
        };

        scope.showFollowersPanel = function () {
          scope.showFollowers = true;
        };

        scope.hideFollowersPanel = function () {
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
                member.status = setMemberStatus(member);
              });
              scope.memberList.push.apply(scope.memberList, members);
            }
          });
        }

        function setMemberStatus (member) {
          if (member.lastInvitedAt) {
            return 'Inivation Pending';
          } else if (member.membership === 'read_only') {
            return 'Following';
          } else {
            return 'Collaborating';
          }
        }

        //
        // Watches and listeners.
        //
        scope.$watch(function () {
          return scope.library.name;
        }, function (newVal, oldVal) {
          if (newVal !== oldVal) {
            scope.$error.name = null;

            scope.userHasEditedSlug = !!scope.library.name;
            scope.library.slug = util.generateSlug(newVal);
          }
        });


        //
        // On link.
        //
        if (scope.modalData) {
          scope.library = _.cloneDeep(scope.modalData.library);
          scope.library.followers.forEach(function (follower) {
            follower.status = 'Following';
          });
          scope.memberList.push.apply(scope.memberList, scope.library.followers);
          scope.offset = 1;
          returnAction = scope.modalData.returnAction || null;
          scope.modifyingExistingLibrary = true;
          scope.userHasEditedSlug = true;
          scope.modalTitle = scope.library.name;
        } else {
          scope.library = {
            'name': '',
            'description': '',
            'slug': '',

            // By default, the create library form selects the "discoverable" visibility for a new library.
            'visibility': 'published'
          };
          scope.modalTitle = 'Create a library';
        }

        nameInput.focus();
      }
    };
  }
]);
