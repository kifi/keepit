'use strict';

angular.module('kifi')

.controller('UserProfileCtrl', [
  '$scope', '$rootScope', '$state', '$stateParams', '$timeout', '$window',
  'env', 'inviteService', 'keepWhoService', 'profileService', 'userProfileActionService', 'modalService', 'libraryService',
  function ($scope, $rootScope, $state, $stateParams, $timeout, $window,
            env, inviteService, keepWhoService, profileService, userProfileActionService, modalService, libraryService) {
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
    // Internal data.
    //
    var ignoreClick = {
      'connect': true
    };
    var $connectEl = null;
    var connectElSelector = '.kf-user-profile-connect';


    //
    // Scope data.
    //
    $scope.userProfileRootUrl = env.origin + '/' + $stateParams.username;
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

      // User id from profile is needed for connection.
      // Use timeout to wait until the connect element is added to the DOM with ng-if.
      $timeout(enableConnectClick);
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

    function enableConnectClick() {
      ignoreClick.connect = false;
      $connectEl = $connectEl || angular.element(connectElSelector);
      $connectEl.attr('href', 'javascript:');  // jshint ignore:line
    }

    function disableConnectClick() {
      ignoreClick.connect = true;
      $connectEl = $connectEl || angular.element(connectElSelector);
      $connectEl.removeAttr('href');
    }


    //
    // Scope methods.
    //
    $scope.connect = function () {
      if (ignoreClick.connect) {
        return;
      }

      disableConnectClick();
      $connectEl = $connectEl || angular.element(connectElSelector);
      $connectEl.text('SENDING REQUEST...');

      inviteService.friendRequest($scope.profile.id).then(function () {
        $connectEl.text('SENT!');
      });
    };

    $scope.isMyLibrary = function(libraryOwnerId) {
      return libraryOwnerId === $scope.profile.id;
    };

    $scope.isHidden = function(library) {
      return library.visibility === 'published' && library.listed === false;
    };

    $scope.openModifyLibrary = function (library) {
      modalService.open({
        template: 'libraries/manageLibraryModal.tpl.html',
        modalData: {
          pane: 'manage',
          library: library,
          returnAction: function () {
            libraryService.getLibraryById(library.id, true).then(function (data) {
              _.assign(library, data.library);
              library.listed = data.listed;
              library.path = data.library.url;
            })['catch'](modalService.openGenericErrorModal);
          }
        }
      });
    };

    // Initialize controller.
    init();
  }
])


.controller('UserProfileLibrariesCtrl', [
  '$scope', '$rootScope', '$state', '$stateParams', 'routeService', 'keepWhoService', 'profileService', 'userProfileActionService', 'userService',
  function ($scope, $rootScope, $state, $stateParams, routeService, keepWhoService, profileService, userProfileActionService, userService) {
    var colors = ['#C764A2', '#E35957', '#FF9430', '#2EC89A', '#3975BF', '#955CB4', '#FAB200'];
    var username = $stateParams.username;
    var fetchPageSize = 12;
    var fetchPageNumber = 0;
    var hasMoreLibraries = true;
    var loading = false;

    $scope.libraryType = $state.current.data.libraryType;
    $scope.libraries = null;

    var deregister$stateChangeSuccess = $rootScope.$on('$stateChangeSuccess', function (event, toState) {
      if (/^userProfile\.libraries\./.test(toState.name)) {
        $scope.libraryType = toState.data.libraryType;
        refetchLibraries();
      }
    });
    $scope.$on('$destroy', deregister$stateChangeSuccess);

    $scope.fetchLibraries = function () {
      if (loading) {
        return;
      }
      loading = true;

      var filter = $scope.libraryType;
      userProfileActionService.getLibraries(username, filter, fetchPageNumber, fetchPageSize).then(function (data) {
        if ($scope.libraryType === filter) {
          hasMoreLibraries = data[filter].length === fetchPageSize;

          var owner = filter === 'own' ? _.extend({username: username}, $scope.profile) : null;
          $scope.libraries = ($scope.libraries || []).concat(data[filter].map(augmentLibrary.bind(null, owner)));

          fetchPageNumber++;
          loading = false;
        }
      });
    };

    $scope.hasMoreLibraries = function () {
      return hasMoreLibraries;
    };

    $scope.getUserProfileUrl = function (user) {
      return userService.getProfileUrl(user.username);
    };

    function resetFetchState() {
      $scope.libraries = null;
      fetchPageNumber = 0;
      hasMoreLibraries = true;
      loading = false;
    }

    function refetchLibraries() {
      resetFetchState();
      $scope.fetchLibraries();
    }

    function augmentLibrary(owner, lib) {
      owner = lib.owner || owner;
      lib.path = '/' + owner.username + '/' + lib.slug;
      lib.owner = owner;
      lib.ownerPicUrl = keepWhoService.getPicUrl(owner, 200);
      if (/^system_/.test(lib.kind)) {
        lib.system = true;
      } else {
        lib.color = lib.color || _.sample(colors);
      }
      lib.imageCss = lib.image ? {
          'background-image': 'url(' + routeService.libraryImageUrl(lib.image.path) + ')',
          'background-position': lib.image.x + '% ' + lib.image.y + '%'
        } : {};
      lib.followers.forEach(function (user) {
        user.picUrl = keepWhoService.getPicUrl(user, 100);
      });
      return lib;
    }
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
