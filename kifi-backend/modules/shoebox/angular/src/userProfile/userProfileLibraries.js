'use strict';

angular.module('kifi')

.controller('UserProfileLibrariesCtrl', [
  '$scope', '$rootScope', '$state', '$stateParams', '$timeout',
  'profileService', 'userProfileActionService', 'modalService', 'userProfilePageNames',
  function ($scope, $rootScope, $state, $stateParams, $timeout,
            profileService, userProfileActionService, modalService, userProfilePageNames) {
    var username = $stateParams.username;
    var fetchPageSize = 12;
    var fetchPageNumber;
    var hasMoreLibraries;
    var loading;
    var newLibraryIds;

    $scope.libraries = null;
    $scope.libraryType = $state.current.name.split('.').pop();

    function resetAndFetchLibraries() {
      fetchPageNumber = 0;
      hasMoreLibraries = true;
      loading = false;
      newLibraryIds = {};
      $scope.libraries = null;
      $scope.fetchLibraries();
    }

    [
      $rootScope.$on('$stateChangeSuccess', function (event, toState) {
        if (/^userProfile\.libraries\./.test(toState.name)) {
          $scope.libraryType = toState.name.split('.').pop();
          resetAndFetchLibraries();
        }
      }),
      $rootScope.$on('libraryDeleted', function (event, libraryId) {
        _.remove($scope.libraries, {id: libraryId});
      }),
      $rootScope.$on('libraryKeepCountChanged', function (event, libraryId, keepCount) {
        (_.find($scope.libraries, {id: libraryId}) || {}).keepCount = keepCount;
      })
    ].forEach(function (deregister) {
      $scope.$on('$destroy', deregister);
    });

    $scope.fetchLibraries = function () {
      if (loading) {
        return;
      }
      loading = true;

      var filter = $scope.libraryType;
      userProfileActionService.getLibraries(username, filter, fetchPageNumber, fetchPageSize).then(function (data) {
        if ($scope.libraryType === filter) {
          var libs = data[filter];
          hasMoreLibraries = libs.length === fetchPageSize;  // important to do before filtering below

          if ($scope.profile.id === profileService.me.id && filter === 'own' && !_.isEmpty(newLibraryIds)) {
            _.remove(libs, function (lib) {
              return lib.id in newLibraryIds;
            });
          }

          $scope.libraries = ($scope.libraries || []).concat(libs);

          fetchPageNumber++;
          loading = false;
        }
      });
    };

    $scope.hasMoreLibraries = function () {
      return hasMoreLibraries;
    };

    $scope.showInvitedLibraries = function () {
      return $scope.profile && $scope.profile.numInvitedLibraries && $scope.viewingOwnProfile;
    };

    $scope.openCreateLibrary = function () {
      function addNewLibAnimationClass(newLibrary) {
        // If the second system library card is under the create-library card,
        // then there are two cards across and the new library will be
        // below and across from the create-library card.
        if ((Math.abs(angular.element('.kf-upl-create-card').offset().left -
                      angular.element('.kf-upl-lib-card').eq(1).offset().left)) < 10) {
          newLibrary.justAddedBelowAcross = true;
        }
        // Otherwise, there are three cards across and the new library will be
        // directly below the create-library-card.
        else {
          newLibrary.justAddedBelow = true;
        }

        $timeout(function () {
          newLibrary.justAddedBelow = false;
          newLibrary.justAddedBelowAcross = false;
        });
      }

      modalService.open({
        template: 'libraries/manageLibraryModal.tpl.html',
        modalData: {
          returnAction: function (newLibrary) {
            addNewLibAnimationClass(newLibrary);
            newLibraryIds[newLibrary.id] = true;

            // Add new library to right behind the two system libraries.
            ($scope.libraries || []).splice(2, 0, newLibrary);
          }
        }
      });
    };

    $scope.trackLibraryNav = function (toLibraryType) {
      $rootScope.$emit('trackUserProfileEvent', 'click', {
        'action': 'clicked' + userProfilePageNames[toLibraryType]
      });
    };

    resetAndFetchLibraries();
  }
])

.directive('kfUserProfileLibraryCard', [
  '$rootScope', '$location', 'profileService', 'libraryService', 'modalService', 'platformService', 'signupService',
  function ($rootScope, $location, profileService, libraryService, modalService, platformService, signupService) {
    // values that are the same for all cards that coexist at any one time
    var currentPageName;
    var currentPageOrigin;

    function canModifyCollaborators(lib) {
      var mem = lib.membership;
      return mem && (mem.access === 'owner' || mem.access === 'read_write' && lib.whoCanInvite === 'collaborator');
    }

    function updateCollaborators(numCollaborators, ignored, scope) {
      var n = 3; // at most 4 circles, one spot reserved for owner
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
              _.assign(library, _.pick(data.library, 'name', 'slug', 'description', 'visibility', 'color', 'numFollowers', 'membership'));
              library.subscriptions = data.subscriptions;
              library.path = data.library.url;
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
      } else if ($rootScope.userLoggedIn === false) {
        return signupService.register({libraryId: lib.id, intent: 'follow'});
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
        var me = _.pick(profileService.me, 'id', 'firstName', 'lastName', 'pictureName', 'username');
        if (membership.access === 'read_write') {  // started collaborating
          if (oldMem && oldMem.access === 'read_only') {
            lib.numFollowers--;
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
        lib.numFollowers--;
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
        libraryOwnerUserName: lib.owner.username,
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
      templateUrl: 'userProfile/userProfileLibraryCard.tpl.html',
      link: function (scope) {
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
          return lib.invite ? 'invited' :
              (scope.myProfile && scope.canWrite && lib.visibility === 'published' && !lib.membership.listed ? 'unlisted' :
                (lib.followers.length ? 'followers' : (lib.lastKept ? 'updated' : '')));
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
      }
    };
  }
]);
