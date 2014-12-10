'use strict';

angular.module('kifi')

.config([
  '$routeProvider', '$locationProvider', '$httpProvider',
  function ($routeProvider, $locationProvider, $httpProvider) {
    $locationProvider
      .html5Mode(true)
      .hashPrefix('!');

    $routeProvider.when('/friends', {
      templateUrl: 'friends/friends.tpl.html'
    }).when('/friends/requests', {
      redirectTo: '/friends'
    }).when('/friends/requests/:network', {
      redirectTo: '/friends'
    }).when('/helprank/:helprank', {
      templateUrl: 'helprank/helprank.tpl.html',
      controller: 'HelpRankCtrl'
    }).when('/', {
      templateUrl: 'recos/recosView.tpl.html'
    }).when('/invite', {
      templateUrl: 'invite/invite.tpl.html'
    }).when('/friends/invite', {
      redirectTo: '/invite'
    }).when('/profile', {
      templateUrl: 'profile/profile.tpl.html',
      controller: 'ProfileCtrl'
    }).when('/recommendations', {
      redirectTo: '/'
    }).when('/find', {
      templateUrl: 'search/search.tpl.html',
      controller: 'SearchCtrl',
      reloadOnSearch: false
    }).when('/keep/:keepId', {
      templateUrl: 'keep/keepView.tpl.html',
      controller: 'KeepViewCtrl'
    }).when('/tags/manage', {
      templateUrl: 'tagManage/tagManage.tpl.html',
      controller: 'ManageTagCtrl'
    }).when('/:username/profile', {
      templateUrl: 'userProfile/userProfile.tpl.html',
      controller: 'UserProfileCtrl'
    })
    // ↓↓↓↓↓ Important: This needs to be last! ↓↓↓↓↓
    .when('/:username/:librarySlug/find', {
      templateUrl: 'libraries/library.tpl.html',
      controller: 'LibraryCtrl',
      resolve: { librarySearch: function () { return true; } },
      reloadOnSearch: false
    })

    .when('/:username/:librarySlug', {
      templateUrl: 'libraries/library.tpl.html',
      controller: 'LibraryCtrl',
      resolve: { librarySearch: function () { return false; } }
    });
    // ↑↑↑↑↑ Important: This needs to be last! ↑↑↑↑↑

    $routeProvider.otherwise({
      redirectTo: '/'
    });

    $httpProvider.defaults.withCredentials = true;
  }
]);
