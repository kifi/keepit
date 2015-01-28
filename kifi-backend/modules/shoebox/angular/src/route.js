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
      .when('/friends/invite', '/invite')
      .when('/friends/requests', '/friends')
      .when('/friends/requests/:network', '/friends')
      .when('/recommendations', '/')
      .otherwise('/');  // last resort

    // Set up the states.
    $stateProvider
      .state('home', {  // Home page.
        url: '/',
        templateUrl: 'recos/recosView.tpl.html'
      })
      .state('friends', {
        url: '/friends',
        templateUrl: 'friends/friends.tpl.html'
      })
      .state('helpRank', {
        url: '/helprank/:helprank',
        templateUrl: 'helprank/helprank.tpl.html',
        controller: 'HelpRankCtrl'
      })
      .state('invite', {
        url: '/invite',
        templateUrl: 'invite/invite.tpl.html'
      })
      .state('keep', {
        url: '/keep/:keepId',
        templateUrl: 'keep/keepView.tpl.html',
        controller: 'KeepViewCtrl'
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
        templateUrl: 'userProfile/userProfileLibrariesList.tpl.html',
        data: {
          libraryType: 'own'
        }
      })
      .state('userProfile.libraries.following', {
        url: '/libraries/following',
        templateUrl: 'userProfile/userProfileLibrariesList.tpl.html',
        data: {
          libraryType: 'following'
        }
      })
      .state('userProfile.libraries.invited', {
        url: '/libraries/invited',
        templateUrl: 'userProfile/userProfileLibrariesList.tpl.html',
        data: {
          libraryType: 'invited'
        }
      })
      .state('userProfile.friends', {
        url: '/friends',
        templateUrl: 'userProfile/userProfilePeople.tpl.html',
        controller: 'UserProfilePeopleCtrl',
        data: {
          peopleType: 'friends'
        }
      })
      .state('userProfile.followers', {
        url: '/followers',
        templateUrl: 'userProfile/userProfilePeople.tpl.html',
        controller: 'UserProfilePeopleCtrl',
        data: {
          peopleType: 'followers'
        }
      })
      .state('userProfile.helped', {
        url: '/helped',
        templateUrl: 'userProfile/userProfileKeeps.tpl.html',
        controller: 'UserProfileKeepsCtrl'
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
        templateUrl: 'search/search.tpl.html',
        controller: 'SearchCtrl'
      });
      // ↑↑↑↑↑ Important: This needs to be last! ↑↑↑↑↑
}]);
