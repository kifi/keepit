'use strict';

angular.module('kifi')

.controller('UserProfileCtrl', [
  '$scope', '$analytics', '$location', '$rootScope', '$state', '$window', 'profile',
  'inviteService', 'originTrackingService', 'profileService', 'installService', 'modalService', 'initParams',
  function ($scope, $analytics, $location, $rootScope, $state, $window, profile,
            inviteService, originTrackingService, profileService, installService, modalService, initParams) {
    //
    // Internal functions.
    //

    function showInstallModal() {
      $scope.platformName = installService.getPlatformName();
      if ($scope.platformName) {
        $scope.thanksVersion = 'installExt';
        $scope.installExtension = installService.triggerInstall;
      } else {
        $scope.thanksVersion = 'notSupported';
      }

      $scope.close = modalService.close;
      modalService.open({
        template: 'signup/thanksForRegisteringModal.tpl.html',
        scope: $scope
      });
    }

    function setCurrentPageOrigin() {
      $scope.currentPageOrigin = 'profilePage' + $scope.stateSuffixToTrackingName[$state.current.name.split('.').pop()];
    }

    function trackPageView() {
      var url = $analytics.settings.pageTracking.basePath + $location.url();
      $analytics.pageTrack(url, originTrackingService.applyAndClear({
        type: $rootScope.userLoggedIn ? 'userProfile' : 'userProfileLanding',
        profileOwnerUserId: profile.id,
        profileOwnedBy: $scope.viewingOwnProfile ? 'viewer' : (profile.isFriend ? 'viewersFriend' : 'other'),
        libraryCount: profile.numLibraries
      }));
    }

    function trackPageClick(attributes) {
      var profileEventTrackAttributes = _.extend(attributes || {}, {
        type: attributes.type || ($rootScope.userLoggedIn ? 'userProfile' : 'userProfileLanding'),
        profileOwnerUserId: profile.id,
        profileOwnedBy: $scope.viewingOwnProfile ? 'viewer' : (profile.isFriend ? 'viewersFriend' : 'other')
      });

      $analytics.eventTrack($rootScope.userLoggedIn ? 'user_clicked_page' : 'visitor_clicked_page', profileEventTrackAttributes);
    }


    //
    // Scope methods and data.
    //

    $scope.stateSuffixToTrackingName = {
      own: 'OwnedLibraries',
      following: 'FollowedLibraries',
      invited: 'InvitedLibraries',
      connections: 'Connections'
    };


    //
    // Watches and listeners.
    //

    [
      $rootScope.$on('$stateChangeSuccess', function (event, toState, toParams, fromState, fromParams) {
        // When routing among the nested states, track page view again.
        if (/^userProfile/.test(toState.name) && /^userProfile/.test(fromState.name) && toParams.username === fromParams.username) {
          trackPageView();
          setCurrentPageOrigin();
        }
      }),
      $rootScope.$on('getCurrentLibrary', function (e, args) {
        args.callback({});
      }),
      $rootScope.$on('trackUserProfileEvent', function (e, eventType, attributes) {
        if (eventType === 'click') {
          trackPageClick(attributes);
        } else if (eventType === 'view') {
          trackPageView(attributes);
        }
      })
    ].forEach(function (deregister) {
      $scope.$on('$destroy', deregister);
    });

    //
    // Initialize controller.
    //

    $window.document.title = profile.firstName + ' ' + profile.lastName + ' • Kifi';
    $scope.profile = _.cloneDeep(profile);
    $scope.viewingOwnProfile = profile.id === profileService.me.id;
    $scope.intent = initParams.getAndClear('intent');

    if ($state.current.name.split('.').pop() === 'own' && profile.numLibraries === 0 && profile.numFollowedLibraries > 0) {
      $state.go('^.following', null, {location: 'replace'});
    } else {
      trackPageView();
      setCurrentPageOrigin();
    }

    if (initParams.getAndClear('install') === '1' && !installService.installedVersion) {
      showInstallModal();
    }
  }
]);
