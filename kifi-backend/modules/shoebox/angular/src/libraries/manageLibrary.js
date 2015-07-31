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

        scope.userHasEditedSlug = false;
        scope.emptySlug = true;
        scope.$error = {};
        scope.showFollowers = false;
        scope.colors = ['#447ab7','#5ab7e7','#4fc49e','#f99457','#dd5c60','#c16c9e','#9166ac'];
        scope.currentPageOrigin = '';
        scope.showSubIntegrations = false;
        scope.newBlankSub = function () { return { 'name': '', 'info': { 'kind': 'slack', 'url': '' }}; };
        scope.showError = false;
        scope.space = {
          current: profileService.me,
          destination: profileService.me
        };
        scope.spaces = profileService.me.orgs ? [profileService.me].concat(profileService.me.orgs) : profileService.me;

        // Find out where we're opening this from
        if ('organization' in scope.modalData) {
          var orgSpace = scope.modalData.organization;
          // Angular is very picky about the object you're using.
          scope.space.current = scope.spaces.filter(function(space) {
            return (space.id === orgSpace.id);
          })[0];
        }
        scope.space.destination = scope.space.current;

        scope.onChangeSpace = function () {
          var currIsOrg = scope.spaceIsOrg(scope.space.current);
          var destIsOrg = scope.spaceIsOrg(scope.space.destination);

          if (currIsOrg !== destIsOrg) {
            if (currIsOrg) {
              if (scope.library.visibility === 'organization') {
                scope.library.visibility = 'secret';
              }
            } else {
              if (scope.library.visibility === 'secret') {
                scope.library.visibility = 'organization';
              }
            }
          }

          // Prevent non-org lib from having visibility === 'organization'
          if (!destIsOrg && scope.library.visibility === 'organization') {
            scope.library.visibility = 'secret';
          }
        };

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

        scope.toggleIntegrations = function() {
          scope.showIntegrations = !scope.showIntegrations;
        };

        scope.addIfEnter = function(event) {
          if (event.keyCode === 13) {
            event.preventDefault();
            scope.addSubscription();
          }
        };

        scope.preventNewline = function(event) {
          if (event.keyCode === 13) {
            event.preventDefault();
          }
        };

        scope.addSubscription = function() {
          var lastSub = scope.library.subscriptions.slice(-1)[0];
          if(lastSub.name === '' && lastSub.info.url === '') {
            return;
          } else {
            scope.library.subscriptions.push(scope.newBlankSub());
          }
        };

        scope.removeSubscription = function (index) {
          scope.library.subscriptions.splice(index,1);
        };


        scope.spaceIsOrg = function (space) {
          return 'numMembers' in space;
        };

        var ownerType = function(space) {
          return scope.spaceIsOrg(space) ? 'org' : 'user';
        };

        scope.saveLibrary = function () {

          if (submitting) {
            return;
          }

          scope.$error = {};

          scope.library.subscriptions.forEach(function(sub) {

            if (sub.name === '' && sub.info.url === '') {
              return;
            }

            if (sub.name) { // slack channels can't have uppercase letters
              sub.name = sub.name.toLowerCase();
            }

            if (!sub.name || sub.name.indexOf(' ') > -1) {
              sub.$error = sub.$error || {};
              sub.$error.name = true;
              scope.$error.general = 'Please enter a valid Slack channel name for each subscription.';
            }

            if (sub.info.url === '' || sub.info.url.match(/https:\/\/hooks.slack.com\/services\/.*\/.*/i) == null) {
              sub.$error = sub.$error || {};
              sub.$error.url = true;
              scope.$error.general = 'Please enter a valid webhook URL for each subscription.';
            }
          });

          if (scope.$error.general) {
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

          var nonEmptySubscriptions = _.filter(scope.library.subscriptions, function(sub){
            return sub.name !== '' && sub.info.url !== '';
          });

          var owner = {};
          owner[ownerType(scope.space.destination)] = scope.space.destination.id;

          libraryService[scope.modifyingExistingLibrary && scope.library.id ? 'modifyLibrary' : 'createLibrary']({
            id: scope.library.id,
            name: scope.library.name,
            description: scope.library.description,
            slug: scope.library.slug,
            visibility: scope.library.visibility,
            listed: scope.library.membership.listed,
            color: colorNames[scope.library.color],
            subscriptions: nonEmptySubscriptions,
            space: owner
          }, true).then(function (resp) {
            libraryService.fetchLibraryInfos(true);

            var newLibrary = resp.data.library;
            newLibrary.listed = resp.data.listed || (resp.data.library.membership && resp.data.library.membership.listed);

            scope.$error = {};
            submitting = false;
            scope.close();

            scope.library.subscriptions = nonEmptySubscriptions;
            if (scope.space.current.id !== scope.space.destination.id) {
              returnAction = null;
            }

            if (!returnAction) {
              $location.url(newLibrary.url || newLibrary.path);
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
                ($state.href('library.keeps') === scope.library.path)) {
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
          scope.library.followers.forEach(function (follower) {
            follower.status = 'Following';
          });
          scope.modifyingExistingLibrary = true;
          scope.emptySlug = false;
          scope.modalTitle = scope.library.name;
          scope.library.subscriptions = scope.library.subscriptions || [];
          if (scope.library.subscriptions.length < 3) {
            scope.library.subscriptions.push(scope.newBlankSub());
          }
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
          scope.library.subscriptions = [scope.newBlankSub()];
          scope.modalTitle = 'Create a library';
        }
        returnAction = scope.modalData && scope.modalData.returnAction;
        scope.currentPageOrigin = scope.modalData && scope.modalData.currentPageOrigin;

        element.find('.manage-lib-name-input').focus();
      }
    };
  }
]);
