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
      templateUrl: 'home/home.tpl.html',
      controller: 'HomeCtrl'
    }).when('/invite', {
      templateUrl: 'invite/invite.tpl.html'
    }).when('/friends/invite', {
      redirectTo: '/invite'
    }).when('/profile', {
      templateUrl: 'profile/profile.tpl.html',
      controller: 'ProfileCtrl'
    }).when('/kifeeeed', {
      templateUrl: 'recos/adhoc.tpl.html'
    }).when('/recommendations', {
      templateUrl: 'recos/recosView.tpl.html'
    }).when('/find', {
      templateUrl: 'search/search.tpl.html',
      controller: 'SearchCtrl'
    }).when('/tag/:tagId', {
      templateUrl: 'tagKeeps/tagKeeps.tpl.html',
      controller: 'TagKeepsCtrl'
    })
    // ↓↓↓↓↓ Important: This needs to be last! ↓↓↓↓↓
    .when('/:username/:librarySlug', {
      templateUrl: 'libraries/library.tpl.html',
      controller: 'LibraryCtrl'
    });
    // ↑↑↑↑↑ Important: This needs to be last! ↑↑↑↑↑

    $routeProvider.otherwise({
      redirectTo: '/'
    });

    $httpProvider.defaults.withCredentials = true;
  }
]);
