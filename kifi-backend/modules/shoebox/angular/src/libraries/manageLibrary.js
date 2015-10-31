'use strict';

angular.module('kifi')

.directive('kfManageLibrary', [
  '$window', '$rootScope', '$location', '$state', 'friendService',
  'libraryService', 'modalService', 'profileService', 'orgProfileService', 'util',
  'LIB_PERMISSION', 'ORG_PERMISSION', 'ORG_SETTING_VALUE',
  function ($window, $rootScope, $location, $state, friendService,
            libraryService, modalService, profileService, orgProfileService, util,
            LIB_PERMISSION, ORG_PERMISSION, ORG_SETTING_VALUE) {
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
        scope.LIB_PERMISSION = LIB_PERMISSION;
        scope.ORG_PERMISSION = ORG_PERMISSION;
        scope.ORG_SETTING_VALUE = ORG_SETTING_VALUE;
        scope.userHasEditedSlug = false;
        scope.emptySlug = true;
        scope.$error = {};
        scope.colors = ['#447ab7','#5ab7e7','#4fc49e','#f99457','#dd5c60','#c16c9e','#9166ac'];
        scope.currentPageOrigin = '';
        scope.showSubIntegrations = false;
        scope.newBlankSub = function () { return { 'name': '', 'info': { 'kind': 'slack', 'url': '' }}; };
        scope.showError = false;
        scope.me = profileService.me;
        scope.libraryProps = {
          inOrg: false,
          selectedOrgId: null
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

        scope.toggleIntegrations = function (e) {
          scope.integrationsOpen = !scope.integrationsOpen;
          if (scope.integrationsOpen) {
            element.find('.dialog-body').animate({
              scrollTop: e.target.getBoundingClientRect().top
            }, 500);
          }
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
          return !!space && !('firstName' in space);
        };

        var ownerType = function(space) {
          return scope.spaceIsOrg(space) ? 'org' : 'user';
        };

        scope.saveLibrary = function () {

          if (submitting) {
            return;
          }

          scope.$error = {};

          validateSubscriptions();

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

          // Create an owner object that declares the type (user/org) for backend.
          var owner;
          // If the space is changing or the library is new
          if (scope.space.current.id !== scope.space.destination.id || !scope.modalData.library) {
            owner = {};
            owner[ownerType(scope.space.destination)] = scope.space.destination.id;
          }

          libraryService[scope.modifyingExistingLibrary && scope.library.id ? 'modifyLibrary' : 'createLibrary']({
            id: scope.library.id,
            name: scope.library.name,
            description: scope.library.description,
            slug: scope.library.slug,
            visibility: scope.library.visibility,
            listed: (scope.library.membership && scope.library.membership.listed) || true,
            color: colorNames[scope.library.color],
            subscriptions: nonEmptySubscriptions,
            orgMemberAccess: scope.library.orgMemberAccess,
            space: owner
          }, true).then(function (resp) {
            // libraryService.fetchLibraryInfos(true);

            var newLibrary = resp.data.library;
            newLibrary.listed = resp.data.listed || (resp.data.library.membership && resp.data.library.membership.listed) || true;

            scope.$error = {};
            submitting = false;
            scope.close();

            scope.library.subscriptions = nonEmptySubscriptions;
            if (scope.space.current.id !== scope.space.destination.id) {
              returnAction = null;
            }

            if (scope.spaceIsOrg(scope.space.destination)) {
              orgProfileService.invalidateOrgProfileCache();
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

        function validateSubscriptions() {
          scope.library.subscriptions.forEach(function(sub) {

            if (sub.name === '' && sub.info.url === '') {
              return;
            }

            if (sub.name) { // slack channels can't have uppercase letters
              sub.name = sub.name.toLowerCase();
            }

            sub.$error = {};
            if (!sub.name || sub.name.indexOf(' ') > -1) {
              sub.$error.name = true;
              scope.$error.general = 'Please enter a valid Slack channel name for each subscription.';
            } else if (sub.info.url === '' || sub.info.url.match(/https:\/\/hooks.slack.com\/services\/.*\/.*/i) == null) {
              sub.$error.url = true;
              scope.$error.general = 'Please enter a valid webhook URL for each subscription.';
            }
          });
        }

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
            if (scope.spaceIsOrg(scope.space.current)) {
              orgProfileService.invalidateOrgProfileCache();
            }

            // If we were on the deleted library's page,
            // return to the space it was in.
            if ($state.is('library.keeps') &&
                $state.href('library.keeps') === scope.library.path) {

              var redirectToSpaceParams = {
                handle: (
                  scope.library.org ? scope.library.org.handle : (
                    scope.library.owner ? scope.library.owner.username : null
                  )
                )
              };

              // If something went wrong, go to the main page
              if (!redirectToSpaceParams.handle) {
                $location.path('/');
              } else {
                $state.go('userOrOrg', redirectToSpaceParams);
              }
            }
          })['catch'](modalService.openGenericErrorModal)['finally'](function () {
            submitting = false;
          });

          scope.close();
        };

        scope.hasPermission = function (permission) {
          var libraryIsNew = !scope.modalData.library;

          if (libraryIsNew) {
            return true;
          } else {
            return scope.library.permissions.indexOf(permission) !== -1;
          }
        };

        scope.showIntegrations = function () {
          return (
            scope.modifyingExistingLibrary &&
            (
              scope.showIntegrationsUpsell() ||
              !scope.spaceIsOrg(scope.space.destination) ||
              (
                scope.library.subscriptions[0] &&
                scope.library.subscriptions[0].url
              )
            )
          );
        };

        scope.showIntegrationsUpsell = function () {
          return (
            scope.spaceIsOrg(scope.space.destination) &&
            !(
              scope.space.destination.viewer.membership &&
              scope.space.destination.viewer.membership.role !== 'admin'
            )
          );
        };

        scope.isIntegrationsEnabled = function () {
          return (
            !scope.spaceIsOrg(scope.space.destination) ||
            scope.space.destination.viewer.permissions.indexOf(ORG_PERMISSION.CREATE_SLACK_INTEGRATION) > -1
          );
        };

        scope.$watch(function () {
          return scope.isIntegrationsEnabled();
        }, function (newValue) {
          if (newValue === false && scope.integrationsOpen === true) {
            scope.integrationsOpen = !scope.integrationsOpen;
          }
        });

        scope.onHoverUpsellIntegration = function () {
          orgProfileService.trackEvent('user_viewed_page', scope.space.destination, { action: 'viewIntegrationUpsell' });
        };

        scope.onClickUpsellIntegration = function () {
          orgProfileService.trackEvent('user_clicked_page', scope.space.destination, { action: 'clickIntegrationUpsell' });
        };

        //
        // On link.
        //

        // Create scope.library
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
            'visibility': 'published',
            'orgMemberAccess': 'read_write'
          };
          scope.library.org = scope.modalData.organization;
          scope.library.membership = {
            access: 'owner',
            listed: true,
            subscribed: false
          };
          scope.library.subscriptions = [scope.newBlankSub()];
          scope.modalTitle = 'Create a library';
        }

        // Set up the spaces.
        scope.spaces = profileService.me.orgs ? [profileService.me].concat(profileService.me.orgs) : profileService.me;

        if (scope.library.org) {
          scope.libraryProps.inOrg = !!scope.library.org;
          scope.libraryProps.selectedOrgId = scope.library.org.id;

          // This library may not actually have "me" as a member
          // Add this library's org to scope.spaces and remove any duplicates
          scope.spaces.push(scope.library.org);
          scope.spaces = _.uniq(scope.spaces, function(entity, key, id) { // jshint ignore:line
            return entity.id;
          });
        }

        var desiredId = (scope.library.org || profileService.me).id;
        scope.space = {
          current: scope.spaces.filter(function (s) { return s.id === desiredId; }).pop()
        };
        // By default, the library will be saved into the library we are already in.
        scope.space.destination = scope.space.current;

        // If it's a new org library, default to org visibility
        if (scope.library.name === '' && scope.spaceIsOrg(scope.space.destination)) {
          scope.library.visibility = 'organization';
        }

        returnAction = scope.modalData && scope.modalData.returnAction;
        scope.currentPageOrigin = scope.modalData && scope.modalData.currentPageOrigin;

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

        [
          $rootScope.$on('$stateChangeSuccess', function () {
            $window.scrollTo(0, 0);
            scope.close();
          })
        ].forEach(function (deregister) {
          scope.$on('$destroy', deregister);
        });

        element.find('.manage-lib-name-input').focus();
      }
    };
  }
]);
