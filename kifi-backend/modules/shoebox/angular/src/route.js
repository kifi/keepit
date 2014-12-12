'use strict';

angular.module('kifi')

.config(function($httpProvider, $locationProvider, $stateProvider, $urlRouterProvider) {
  $locationProvider
    .html5Mode(true)
    .hashPrefix('!');

  $httpProvider.defaults.withCredentials = true;

  // For any unmatched url, redirect to '/'
  $urlRouterProvider.otherwise('/');

  // Now set up the states
  $stateProvider
    .state('/', {
      url: '/',
      templateUrl: 'recos/recosView.tpl.html'
    })
    .state('friends', {
      url: '/friends',
      templateUrl: 'friends/friends.tpl.html'
    })

    .state('userProfile', {  // Shows "My" libraries in the "libraries" tab.
      url: '/:username',
      templateUrl: 'userProfile/userProfile.tpl.html',
      controller: 'UserProfileCtrl'
    })
    .state('userProfile.myLibraries', {
      url: '',
      templateUrl: 'userProfile/test1.tpl.html'
    })
    .state('userProfile.followingLibraries', {
      url: '/following',
      templateUrl: 'userProfile/test2.tpl.html'
    })
    .state('userProfile.invitedLibraries', {
      url: '/invited',
      templateUrl: 'userProfile/test3.tpl.html'
    })

    // For testing only.
    .state('state1', {
      url: '/state1',
      templateUrl: 'partials/state1.html'
    })
    .state('state1.list', {
      url: '/list',
      templateUrl: 'partials/state1.list.html',
      controller: function($scope) {
        $scope.items = ['A', 'List', 'Of', 'Items'];
      }
    })
    .state('state2', {
      url: '/state2',
      templateUrl: 'partials/state2.html'
    })
    .state('state2.list', {
      url: '/list',
      templateUrl: 'partials/state2.list.html',
      controller: function($scope) {
        $scope.things = ['A', 'Set', 'Of', 'Things'];
      }
    });
});

// .config([
//   '$routeProvider', '$locationProvider', '$httpProvider',
//   function ($routeProvider, $locationProvider, $httpProvider) {
//     $locationProvider
//       .html5Mode(true)
//       .hashPrefix('!');

//     $routeProvider.when('/friends', {
//       templateUrl: 'friends/friends.tpl.html'
//     }).when('/friends/requests', {
//       redirectTo: '/friends'
//     }).when('/friends/requests/:network', {
//       redirectTo: '/friends'
//     }).when('/helprank/:helprank', {
//       templateUrl: 'helprank/helprank.tpl.html',
//       controller: 'HelpRankCtrl'
//     }).when('/', {
//       templateUrl: 'recos/recosView.tpl.html'
//     }).when('/invite', {
//       templateUrl: 'invite/invite.tpl.html'
//     }).when('/friends/invite', {
//       redirectTo: '/invite'
//     }).when('/profile', {
//       templateUrl: 'profile/profile.tpl.html',
//       controller: 'ProfileCtrl'
//     }).when('/recommendations', {
//       redirectTo: '/'
//     }).when('/find', {
//       templateUrl: 'search/search.tpl.html',
//       controller: 'SearchCtrl',
//       reloadOnSearch: false
//     }).when('/keep/:keepId', {
//       templateUrl: 'keep/keepView.tpl.html',
//       controller: 'KeepViewCtrl'
//     }).when('/tags/manage', {
//       templateUrl: 'tagManage/tagManage.tpl.html',
//       controller: 'ManageTagCtrl'
//     }).when('/:username', {
//       templateUrl: 'userProfile/userProfile.tpl.html',
//       controller: 'UserProfileCtrl'
//     })
//     // ↓↓↓↓↓ Important: This needs to be last! ↓↓↓↓↓
//     .when('/:username/:librarySlug/find', {
//       templateUrl: 'libraries/library.tpl.html',
//       controller: 'LibraryCtrl',
//       resolve: { librarySearch: function () { return true; } },
//       reloadOnSearch: false
//     })

//     .when('/:username/:librarySlug', {
//       templateUrl: 'libraries/library.tpl.html',
//       controller: 'LibraryCtrl',
//       resolve: { librarySearch: function () { return false; } }
//     });
//     // ↑↑↑↑↑ Important: This needs to be last! ↑↑↑↑↑

//     $routeProvider.otherwise({
//       redirectTo: '/'
//     });

//     $httpProvider.defaults.withCredentials = true;
//   }
// ]);
