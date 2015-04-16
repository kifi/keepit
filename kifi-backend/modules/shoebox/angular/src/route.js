'use strict';

angular.module('kifi')

.config(['$httpProvider', '$locationProvider', '$stateProvider', '$urlRouterProvider',
  function($httpProvider, $locationProvider, $stateProvider, $urlRouterProvider) {
    $locationProvider
      .html5Mode(true)
      .hashPrefix('!');

    $httpProvider.defaults.withCredentials = true;

    // URL redirects.
    var trailingSlashRe = /^([^?#]+)\/([?#].*|$)/;
    $urlRouterProvider
      .rule(function ($injector, $location) {
        var match = $location.url().match(trailingSlashRe);
        if (match) {
          return match[1] + match[2]; // remove trailing slash
        }
      })
      .when('/:username/libraries', '/:username')
      .otherwise('/');  // last resort

    // Set up the states.
    $stateProvider
      .state('home', {  // Home page.
        url: '/',
        templateUrl: 'recos/recosView.tpl.html'
      })
      .state('invite', {
        url: '/invite',
        templateUrl: 'invite/invite.tpl.html'
      })
      .state('manageTags', {
        url: '/tags/manage',
        templateUrl: 'tagManage/tagManage.tpl.html',
        controller: 'ManageTagCtrl'
      })
      .state('settings', {
        url: '/profile',
        templateUrl: 'profile/profile.tpl.html',
        controller: 'ProfileCtrl'
      })
      .state('search', {
        url: '/find?q&f',
        templateUrl: 'search/search.tpl.html',
        controller: 'SearchCtrl',
        resolve: {
          library: angular.noop
        },
        reloadOnSearch: false  // controller handles search query changes itself
      })
      .state('userProfile', {
        url: '/:username',
        templateUrl: 'userProfile/userProfile.tpl.html',
        controller: 'UserProfileCtrl',
        resolve: {
          userProfileActionService: 'userProfileActionService',
          profile: ['userProfileActionService', '$stateParams', function (userProfileActionService, $stateParams) {
            return userProfileActionService.getProfile($stateParams.username);
          }]
        },
        'abstract': true
      })
      .state('userProfile.libraries', {
        url: '',
        templateUrl: 'userProfile/userProfileLibraries.tpl.html',
        controller: 'UserProfileLibrariesCtrl',
        'abstract': true
      })
      .state('userProfile.libraries.own', {
        url: '',
        templateUrl: 'userProfile/userProfileLibrariesList.tpl.html'
      })
      .state('userProfile.libraries.following', {
        url: '/libraries/following',
        templateUrl: 'userProfile/userProfileLibrariesList.tpl.html'
      })
      .state('userProfile.libraries.invited', {
        url: '/libraries/invited',
        templateUrl: 'userProfile/userProfileLibrariesList.tpl.html'
      })
      .state('userProfile.connections', {
        url: '/connections',
        templateUrl: 'userProfile/userProfileConnections.tpl.html',
        controller: 'UserProfileConnectionsCtrl'
      })
      .state('userProfile.followers', {
        url: '/followers',
        templateUrl: 'userProfile/userProfileFollowers.tpl.html',
        controller: 'UserProfileFollowersCtrl'
      })

      // ↓↓↓↓↓ Important: This needs to be last! ↓↓↓↓↓
      .state('library', {
        url: '/:username/:librarySlug?authToken',
        templateUrl: 'libraries/library.tpl.html',
        controller: 'LibraryCtrl',
        resolve: {
          libraryService: 'libraryService',
          library: ['libraryService', '$stateParams', function (libraryService, $stateParams) {
            return libraryService.getLibraryByUserSlug($stateParams.username, $stateParams.librarySlug, $stateParams.authToken);
          }]
        },
        'abstract': true
      })
      .state('library.keeps', {
        url: '',
        templateUrl: 'libraries/libraryKeeps.tpl.html'
      })
      .state('library.search', {
        url: '/find?q&f',
        templateUrl: 'search/matchingKeeps.tpl.html',
        controller: 'SearchCtrl',
        reloadOnSearch: false  // controller handles search query changes itself
      });
      // ↑↑↑↑↑ Important: This needs to be last! ↑↑↑↑↑
}]);
