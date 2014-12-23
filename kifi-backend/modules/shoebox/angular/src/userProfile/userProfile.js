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
      { name: 'Libraries', /*routeState: 'userProfile.libraries.own',*/ countFieldName: 'numLibraries' },  // routeState is V2
      { name: 'Keeps', countFieldName: 'numKeeps' }  // V1 only

      /*
       * V2
       */
      // { name: 'Friends', routeState: 'userProfile.friends', countFieldName: 'numFriends' },
      // { name: 'Followers', routeState: 'userProfile.followers', countFieldName: 'numFollowers' },
      // { name: 'Helped', routeState: 'userProfile.helped', countFieldName: 'numRekeeps' }
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
      $scope.profile.picUrl = keepWhoService.getPicUrl($scope.profile, 200);
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
  '$scope', '$rootScope', '$state', '$stateParams', 'util', 'routeService', 'keepWhoService', 'profileService', 'userProfileActionService',
  function ($scope, $rootScope, $state, $stateParams, util, routeService, keepWhoService, profileService, userProfileActionService) {
    var colors = ['#C764A2', '#E35957', '#FF9430', '#2EC89A', '#3975BF', '#955CB4', '#FAB200', '#FAB200', '#FAB200'];
    var username = $stateParams.username;

    $scope.libraryType = $state.current.data.libraryType;
    $scope.libraries = [];

    userProfileActionService.getLibraries(username, 'own').then(function (data) {
      $scope.libraries = data.own.map(function augment(lib) {
        lib.path = '/' + username + '/' + lib.slug;
        lib.owner = $scope.profile;
        lib.ownerPicUrl = $scope.profile.picUrl;
        lib.color = lib.color || _.sample(colors);
        lib.imageCss = lib.image ? {
            'background-image': 'url(' + routeService.libraryImageUrl(lib.image.path) + ')',
            'background-position': lib.image.x + '% ' + lib.image.y + '%'
          } : {};
        return lib;
      });
    });

    var deregister$stateChangeSuccess = $rootScope.$on('$stateChangeSuccess', function (event, toState) {
      if (util.startsWith(toState.name, 'userProfile.libraries')) {
        $scope.libraryType = toState.data.libraryType;
      }
    });
    $scope.$on('$destroy', deregister$stateChangeSuccess);
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
