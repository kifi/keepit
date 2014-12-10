'use strict';

angular.module('kifi')

.controller('UserProfileCtrl', [
  '$scope', '$rootScope', '$state', '$stateParams', 'keepWhoService', 'profileService', 'userProfileActionService',
  function ($scope, $rootScope, $state, $stateParams, keepWhoService, profileService, userProfileActionService) {
    //
    // Configs.
    //
    var userNavLinksConfig = [
      { name: 'Libraries', routeState: 'userProfile.libraries.my', countFieldName: 'numLibraries' },
      // For V1 only.
      // { name: 'Keeps', routeState: '/keeps'/* for testing only */, countFieldName: 'numKeeps' }

      /*
       * For V2.
       */
      { name: 'FRIENDS', routeState: 'userProfile.friends', countFieldName: 'numFriends' },
      { name: 'FOLLOWERS', routeState: 'userProfile.followers', countFieldName: 'numFollowers' },
      { name: 'HELPED', routeState: 'userProfile.helped', countFieldName: 'helpedRekeep' }
    ];

    var libraryNavLinksConfig = [
      { name: 'MY', routeState: 'userProfile.libraries.my' },
      { name: 'FOLLOWING', routeState: 'userProfile.libraries.following' },
      { name: 'INVITED', routeState: 'userProfile.libraries.invited' }
    ];


    //
    // Scope data.
    //
    $scope.profile = null;
    $scope.userLoggedIn = false;
    $scope.viewingOwnProfile = false;
    $scope.canConnectToUser = false;
    $scope.userNavLinks = [];
    $scope.libraryNavLinks = [];
    $scope.optionalAction = null;


    //
    // Internal functions.
    //
    function init() {
      // If user is not in experiment or the url doesn't include the experiement query parameter,
      // redirect to home page.
      if (!$stateParams.upb &&   // upb = User Profiles Beta
          !(profileService.me.experiments && profileService.me.experiments.indexOf('profiles_beta') > -1)) {
        $state.go('home');
      }

      var username = $stateParams.username;

      userProfileActionService.getProfile(username).then(function (profile) {
        initProfile(profile);
        initViewingUserStatus();
        initUserNavLinks();
      });

      // Need to add in a check for whether we're on libraries tab before we do this.
      initLibraries([]);
      initLibraryNavLinks();
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

    function initLibraryNavLinks() {
      $scope.libraryNavLinks = _.map(libraryNavLinksConfig, function (config) {
        return {
          name: config.name,
          routeState: config.routeState
        };
      });

      // For dev testing only.
      $scope.libraryNavLinks[0].selected = true;
    }

    function initLibraries(libraries) {
      $scope.libraries = libraries;
    }


    // Initialize controller.
    init();
  }
])


.controller('UserProfileLibrariesCtrl', [
  '$scope',
  function (/* $scope */) {
    // Noop for now.
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
