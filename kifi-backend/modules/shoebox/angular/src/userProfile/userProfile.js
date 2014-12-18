'use strict';

angular.module('kifi')

.controller('UserProfileCtrl', [
  '$scope', '$rootScope', '$state', '$stateParams', '$window',
  'keepWhoService', 'profileService', 'userProfileActionService',
  function ($scope, $rootScope, $state, $stateParams, $window,
            keepWhoService, profileService, userProfileActionService) {
    //
    // Configs.
    //
    var userNavLinksConfig = [
      { name: 'Libraries', countFieldName: 'numLibraries' },  // For v2, add: routeState: 'userProfile.libraries.my',
      // For V1 only.
      { name: 'Keeps', countFieldName: 'numKeeps' }

      /*
       * For V2.
       */
      // { name: 'FRIENDS', routeState: 'userProfile.friends', countFieldName: 'numFriends' },
      // { name: 'FOLLOWERS', routeState: 'userProfile.followers', countFieldName: 'numFollowers' },
      // { name: 'HELPED', routeState: 'userProfile.helped', countFieldName: 'helpedRekeep' }
    ];


    //
    // Scope data.
    //
    $scope.profile = null;
    $scope.userLoggedIn = false;
    $scope.viewingOwnProfile = false;
    $scope.canConnectToUser = false;
    $scope.userNavLinks = [];
    $scope.optionalAction = null;


    //
    // Internal functions.
    //
    function init() {
      // Logged in users who are in the "profiles_beta" experiment and logged out users
      // who have the "upb" query parameter can see this page.
      // Otherwise, redirect to home.
      if ($rootScope.userLoggedIn &&
          !(profileService.me.experiments && profileService.me.experiments.indexOf('profiles_beta') > -1)) {
        $state.go('home');
      }
      if (!$rootScope.userLoggedIn && !$stateParams.upb) {
        $state.go('home');
        $window.location = '/';
      }

      var username = $stateParams.username;

      userProfileActionService.getProfile(username).then(function (profile) {
        initProfile(profile);
        initViewingUserStatus();
        initUserNavLinks();
      });
    }

    function initProfile(profile) {
      $scope.profile = _.cloneDeep(profile);

      // Picture URL.
      $scope.profile.picUrl = keepWhoService.getPicUrl({
        id: $scope.profile.id,
        pictureName: $scope.profile.pictureName
      }, 100);
    }

    function initViewingUserStatus() {
      $scope.userLoggedIn = $rootScope.userLoggedIn;
      $scope.viewingOwnProfile = $scope.profile.id === profileService.me.id;
      $scope.canConnectToUser = $scope.userLoggedIn && !$scope.viewingOwnProfile && !$scope.profile.friendsWith;
      $scope.optionalAction = ($scope.viewingOwnProfile ? 'settings' : false) ||
                              ($scope.canConnectToUser ? 'connect' : false);
    }

    function initUserNavLinks() {
      $scope.userNavLinks = _.map(userNavLinksConfig, function (config) {
        return {
          name: config.name,
          count: $scope.profile[config.countFieldName],
          routeState: config.routeState
        };
      });
    }


    // Initialize controller.
    init();
  }
])


.controller('UserProfileLibrariesCtrl', [
  '$scope', '$rootScope', '$state', 'util',
  function ($scope, $rootScope, $state, util) {
    //
    // Configs.
    //
    var libraryNavLinksConfig = [
      { type: 'my', label: 'MY', routeState: 'userProfile.libraries.my' },
      { type: 'following', label: 'FOLLOWING', routeState: 'userProfile.libraries.following' },
      { type: 'invited', label: 'INVITED', routeState: 'userProfile.libraries.invited' }
    ];


    //
    // Scope data.
    //
    $scope.libraryNavLinks = [];


    //
    // Internal functions.
    //
    function init() {
      initLibraryNavLinks();
    }

    function updateSelectedLibraryNavLink() {
      _.forEach($scope.libraryNavLinks, function (navLink) {
        navLink.selected = navLink.type === $state.current.data.libraryType;
      });
    }

    function initLibraryNavLinks() {
      $scope.libraryNavLinks = libraryNavLinksConfig;
      updateSelectedLibraryNavLink();
    }


    //
    // Watches and listeners.
    //
    var deregisterUpdateSelectedLibraryNavLink = $rootScope.$on('$stateChangeSuccess', function (event, toState) {
      if (util.startsWith(toState.name, 'userProfile.libraries')) {
        updateSelectedLibraryNavLink();
      }
    });
    $scope.$on('$destroy', deregisterUpdateSelectedLibraryNavLink);


    // Initialize controller.
    init();
  }
])


.controller('UserProfileLibrariesListCtrl', [
  '$scope', '$state',
  function ($scope, $state) {
    $scope.libraryType = $state.current.data.libraryType;
  }
])


.controller('UserProfilePeopleCtrl', [
  '$scope', '$state',
  function ($scope, $state) {
    $scope.peopleType = $state.current.data.peopleType;
  }
])


.controller('UserProfileKeepsCtrl', [
  '$scope',
  function ($scope) {
    $scope.keepType = 'Helped Rekeep';
  }
]);
