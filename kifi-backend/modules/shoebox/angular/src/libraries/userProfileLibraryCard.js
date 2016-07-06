'use strict';

angular.module('kifi')

.directive('kfUserProfileLibraryCard', [
  '$rootScope', '$location', '$stateParams', 'profileService', 'libraryService', 'modalService',
  'platformService', 'LIB_PERMISSION', 'ORG_SETTING_VALUE', 'orgProfileService',
  function ($rootScope, $location, $stateParams, profileService, libraryService, modalService,
            platformService, LIB_PERMISSION, ORG_SETTING_VALUE, orgProfileService) {
    // values that are the same for all cards that coexist at any one time
    var currentPageName;
    var currentPageOrigin;

    function canModifyCollaborators(lib) {
      return (
        lib.membership && (
          lib.permissions.indexOf(LIB_PERMISSION.INVITE_COLLABORATORS) !== -1 ||
          lib.permissions.indexOf(LIB_PERMISSION.REMOVE_MEMBERS) !== -1
        )
      );
    }

    function updateCollaborators(numCollaborators, ignored, scope) {
      var n = scope.lib.org ? 2 : 3; // at most 4 circles, one spot reserved for owner
      if (canModifyCollaborators(scope.lib)) {
        n--; // one spot reserved for add collaborator button
      }
      scope.maxNumCollaboratorsToShow = numCollaborators > n ? n - 1 : n;  // one spot may be reserved for +N button
    }

    function openModifyLibrary(library) {
      modalService.open({
        template: 'libraries/manageLibraryModal.tpl.html',
        modalData: {
          pane: 'manage',
          library: library,
          currentPageOrigin: currentPageOrigin,
          returnAction: function () {
            libraryService.getLibraryById(library.id, true).then(function (data) {
              _.assign(library, _.pick(data.library, 'name', 'slug', 'description', 'visibility', 'color', 'numFollowers',
                'membership', 'orgMemberAccess', 'whoCanComment')
              );
              library.path = data.library.path || data.library.url;
              library.followers = _.take(data.library.followers, 3);
            })['catch'](modalService.openGenericErrorModal);
          }
        }
      });
    }

    function openFollowersList(lib) {
      if (platformService.isSupportedMobilePlatform()) {
        return;
      }

      modalService.open({
        template: 'libraries/libraryFollowersModal.tpl.html',
        modalData: {
          library: lib,
          currentPageOrigin: currentPageOrigin
        }
      });
    }

    function onFollowButtonClick(scope, lib, $event) {
      if (platformService.isSupportedMobilePlatform()) {
        var url = $location.absUrl();
        platformService.goToAppOrStore(url + (url.indexOf('?') > 0 ? '&' : '?') + 'follow=true');
        return;
      }
      $event.target.disabled = true;
      libraryService[lib.membership ? 'leaveLibrary' : 'joinLibrary'](lib.id).then(function (membership) {
        onMembershipChange(scope, lib, membership);
      })['catch'](function (resp) {
        modalService.openGenericErrorModal({
          modalData: resp.status === 403 && resp.data.error === 'cant_join_nonpublished_library' ? {
            genericErrorMessage: 'Sorry, the owner of this library has made it private. You’ll need an invitation to follow it.'
          } : {}
        });
      })['finally'](function () {
        $event.target.disabled = false;
      });
    }

    function onCollabButtonClick(scope, lib, $event) {
      $event.target.disabled = true;
      libraryService.joinLibrary(lib.id).then(function (membership) {
        onMembershipChange(scope, lib, membership);
      })['catch'](modalService.openGenericErrorModal)
      ['finally'](function () {
        $event.target.disabled = false;
      });
    }

    function onMembershipChange(scope, lib, membership) {
      var oldMem = lib.membership;
      lib.membership = membership;
      if (membership) {
        lib.invite = null;
        var me = _.pick(profileService.me, 'id', 'firstName', 'lastName', 'pictureName', 'handle');
        if (membership.access === 'read_write') {  // started collaborating
          if (oldMem && oldMem.access === 'read_only') {
            if (lib.numFollowers > 0) { // jshint ignore: line
              lib.numFollowers--;
            }
            _.remove(lib.followers, {id: profileService.me.id});
          }
          lib.numCollaborators++;
          lib.collaborators.push(me);
        } else if (membership.access === 'read_only') {  // started following
          lib.numFollowers++;
          if (lib.followers.length < 3 && me.pictureName !== '0.jpg') {
            lib.followers.push(me);
          }
        }
      } else {  // stopped following
        if (lib.numFollowers > 0) {
          lib.numFollowers--;
        }
        _.remove(lib.followers, {id: profileService.me.id});
      }
      scope.canWrite = membership && {owner: true, read_write: true}[membership.access] || false;
    }

    function toggleSubscribed(lib) {
      libraryService.updateSubscriptionToLibrary(lib.id, !lib.membership.subscribed).then(function() {
        lib.membership.subscribed = !lib.membership.subscribed;
      })['catch'](modalService.openGenericErrorModal);
    }

    function trackUplCardClick(lib, subAction) {
      $rootScope.$emit('trackUserProfileEvent', 'click', {
        action: 'clickedLibraryCard',
        subAction: subAction,
        libraryName: lib.name,
        libraryOwnerUserName: lib.owner.handle,
        libraryId: lib.id,
        libraryOwnerUserId: lib.owner.id,
        profileTab: currentPageName
      });
    }

    function openCollaboratorsList(lib) {
      modalService.open({
          template: 'libraries/libraryMembersModal.tpl.html',
          modalData: {
            library: lib,
            canManageMembers: canModifyCollaborators(lib),
            amOwner: (lib.membership || {}).access === 'owner',
            filterType: 'collaborators_only',
            currentPageOrigin: currentPageOrigin
          }
        });
    }

    function hasPermission(permission) {
      var scope = this;
      return scope.lib.permissions.indexOf(permission) !== -1;
    }

    function isMutable() {
      var scope = this;
      var kind = scope.lib.kind;
      return (kind === 'user_created' || kind === 'slack_channel');
    }

    function getMeOrg() {
      var scope = this;
      var me = profileService.me;
      var org = scope.lib.org;

      if (!org) {
        return {};
      } else {
        return (me.orgs || []).filter(function (o) { return o.id === org.id; })[0];
      }
    }

    function onClickUpsellEditLibrary(organization) {
      orgProfileService.trackEvent('user_clicked_page', organization, { action: 'clickEditLibraryUpsell' });
    }

    function onHoverUpsellEditLibrary(organization) {
      orgProfileService.trackEvent('user_viewed_page', organization, { action: 'viewEditLibraryUpsell' });
    }

    return {
      restrict: 'A',
      replace: true,
      scope: {
        lib: '=kfUserProfileLibraryCard',
        profileId: '@',
        libraryType: '@',
        currentPageName: '@',
        currentPageOrigin: '@'
      },
      templateUrl: 'libraries/userProfileLibraryCard.tpl.html',
      link: function (scope) {
        scope.LIB_PERMISSION = LIB_PERMISSION;
        scope.ORG_SETTING_VALUE = ORG_SETTING_VALUE;
        // cheaper to stash than to bind functions that need them
        currentPageName = scope.currentPageName;
        currentPageOrigin = scope.currentPageOrigin;

        var lib = scope.lib;
        var mem = lib.membership || {};
        if (canModifyCollaborators(lib)) {
          scope.$watch('lib.numCollaborators', updateCollaborators);
        } else {
          updateCollaborators(scope.lib.numCollaborators, null, scope);
        }

        scope.myProfile = profileService.me.id === scope.profileId;
        scope.isOwner = mem.access === 'owner';
        scope.canWrite = scope.isOwner || mem.access === 'read_write';

        scope.$watch(function decideFooterKind() {
          if (lib.invite) {
            return 'invited';
          } else if (scope.myProfile && scope.canWrite && lib.visibility === 'published' && !lib.membership.listed) {
            return 'unlisted';
          } else if (lib.followers && lib.followers.length) {
            return 'followers';
          } else if (lib.lastKept) {
            return 'updated';
          } else {
            return '';
          }
        }, function (kind) {
          scope.footerKind = kind;
        });

        // not copying all of these functions for each card, to avoid the memory hit
        scope.openModifyLibrary = openModifyLibrary;
        scope.openFollowersList = openFollowersList;
        scope.openCollaboratorsList = openCollaboratorsList;
        scope.onFollowButtonClick = onFollowButtonClick;
        scope.onCollabButtonClick = onCollabButtonClick;
        scope.toggleSubscribed = toggleSubscribed;
        scope.trackUplCardClick = trackUplCardClick;
        scope.hasPermission = hasPermission;
        scope.isMutable = isMutable;
        scope.getMeOrg = getMeOrg;
        scope.onClickUpsellEditLibrary = onClickUpsellEditLibrary;
        scope.onHoverUpsellEditLibrary = onHoverUpsellEditLibrary;
      }
    };
  }
]);
