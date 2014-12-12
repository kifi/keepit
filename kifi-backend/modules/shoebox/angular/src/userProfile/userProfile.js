'use strict';

angular.module('kifi')

.controller('UserProfileCtrl', [
  '$scope', '$rootScope', '$routeParams', 'keepWhoService', 'profileService', 'userProfileStubService',
  function ($scope, $rootScope, $routeParams, keepWhoService, profileService, userProfileStubService) {
    //
    // Configs.
    //
    var userNavLinksConfig = [
      { name: 'Libraries', subpath: '/', countFieldName: 'numLibraries' },
      { name: 'Keeps', subpath: '/keeps'/* for testing only */, countFieldName: 'numKeeps' }

      /*
       * For V2.
       */
      // { name: 'FRIENDS', subpath: '/friends', countFieldName: 'numFriends' },
      // { name: 'FOLLOWERS', subpath: '/followers', countFieldName: 'numFollowers' },
      // { name: 'HELPED', subpath: '/helped/rekeep', countFieldName: 'helpedRekeep' }
    ];

    var libraryNavLinksConfig = [
      { name: 'MY', subpath: '/' },
      { name: 'FOLLOWING', subpath :'/following' },
      { name: 'INVITED', subpath: '/invited' }
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
      var username = $routeParams.username;

      userProfileStubService.getProfile(username).then(function (profile) {
        initProfile(profile);
        initViewingUserStatus();
        initUserNavLinks();

        // Need to add in a check for whether we're on the libraries tab.
        var libraryType = 'my';  // hard-coded for now.
        userProfileStubService.getLibraries($scope.profile.id, libraryType).then(function (libraries) {
          initLibraries(libraries);
        });
      });

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
          url: getFullUserNavLinkPath(config.subpath),
          count: $scope.profile[config.countFieldName]
        };
      });
    }

    function initLibraryNavLinks() {
      $scope.libraryNavLinks = _.map(libraryNavLinksConfig, function (config) {
        return {
          name: config.name,
          url: getFullUserNavLinkPath(config.subpath)
        };
      });

      // For dev testing only.
      $scope.libraryNavLinks[0].selected = true;
    }

    function initLibraries(libraries) {
      $scope.libraries = libraries;
    }

    function getFullUserNavLinkPath(subpath) {
      // Return a full path to the user nav link.
      // Example:
      //   subpath: '/friends'
      //   returns: '/:username/friends'
      return $routeParams.username + subpath;
    }


    // Initialize controller.
    init();
  }
]);
