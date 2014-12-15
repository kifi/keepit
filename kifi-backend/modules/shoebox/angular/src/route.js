'use strict';

angular.module('kifi')

.config(function($httpProvider, $locationProvider, $stateProvider, $urlRouterProvider) {
  $locationProvider
    .html5Mode(true)
    .hashPrefix('!');

  $httpProvider.defaults.withCredentials = true;

  // For any unmatched url, redirect to '/'.
  $urlRouterProvider.otherwise('/');

  // Now set up the states.
  $stateProvider
    .state('/', {  // Home page.
      url: '/',
      templateUrl: 'recos/recosView.tpl.html'
    })
    .state('friends', {
      url: '/friends',
      templateUrl: 'friends/friends.tpl.html'
    })
    .state('userProfile', {
      url: '/:username',
      templateUrl: 'userProfile/userProfile.tpl.html',
      controller: 'UserProfileCtrl',
      'abstract': true
    })
      .state('userProfile.libraries', {
        url: '',
        templateUrl: 'userProfile/userProfileLibraries.tpl.html',
        controller: 'UserProfileLibrariesCtrl',
        'abstract': true
      })
        .state('userProfile.libraries.my', {
          url: '',
          templateUrl: 'userProfile/userProfileLibrariesList.tpl.html',
          controller: 'UserProfileLibrariesListCtrl',
          resolve: { libraryType: function () { return 'My'; } }
        })
        .state('userProfile.libraries.following', {
          url: '/following',
          templateUrl: 'userProfile/userProfileLibrariesList.tpl.html',
          controller: 'UserProfileLibrariesListCtrl',
          resolve: { libraryType: function () { return 'Following'; } }
        })
        .state('userProfile.libraries.invited', {
          url: '/invited',
          templateUrl: 'userProfile/userProfileLibrariesList.tpl.html',
          controller: 'UserProfileLibrariesListCtrl',
          resolve: { libraryType: function () { return 'Invited'; } }
        })
      .state('userProfile.friends', {
        url: '/friends',
        templateUrl: 'userProfile/userProfilePeople.tpl.html',
        controller: 'UserProfilePeopleCtrl',
        resolve: { peopleType: function () { return 'Friends'; } }
      })
      .state('userProfile.followers', {
        url: '/followers',
        templateUrl: 'userProfile/userProfilePeople.tpl.html',
        controller: 'UserProfilePeopleCtrl',
        resolve: { peopleType: function () { return 'Followers'; } }
      })
      .state('userProfile.helped', {
        url: '/helped',
        templateUrl: 'userProfile/userProfileKeeps.tpl.html',
        controller: 'UserProfileKeepsCtrl'
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
