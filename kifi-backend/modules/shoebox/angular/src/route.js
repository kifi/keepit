'use strict';

angular.module('kifi')

.config(function($httpProvider, $locationProvider, $stateProvider, $urlRouterProvider) {
  $locationProvider
    .html5Mode(true)
    .hashPrefix('!');

  $httpProvider.defaults.withCredentials = true;

  // URL redirects.
  $urlRouterProvider
    .when('/friends/invite', '/invite')
    .when('/friends/requests', '/friends')
    .when('/friends/requests/:network', 'friends')
    .when('/recommendations', '/')

    // For any unmatched url, redirect to '/'.
    .otherwise('/');

  // Set up the states.
  $stateProvider
    .state('recos', {  // Home page.
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

      // SearchCtrl manually reloads search results on search input change in a debounced function.
      reloadOnSearch: false
    })
    .state('userProfile', {
      url: '/:username?upb',
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
          data: {
            libraryType: 'My'
          }
        })
        .state('userProfile.libraries.following', {
          url: '/libraries/following',
          templateUrl: 'userProfile/userProfileLibrariesList.tpl.html',
          controller: 'UserProfileLibrariesListCtrl',
          data: {
            libraryType: 'Following'
          }
        })
        .state('userProfile.libraries.invited', {
          url: '/libraries/invited',
          templateUrl: 'userProfile/userProfileLibrariesList.tpl.html',
          controller: 'UserProfileLibrariesListCtrl',
          data: {
            libraryType: 'Invited'
          }
        })
      .state('userProfile.friends', {
        url: '/friends',
        templateUrl: 'userProfile/userProfilePeople.tpl.html',
        controller: 'UserProfilePeopleCtrl',
        data: {
          peopleType: 'Friends'
        }
      })
      .state('userProfile.followers', {
        url: '/followers',
        templateUrl: 'userProfile/userProfilePeople.tpl.html',
        controller: 'UserProfilePeopleCtrl',
        data: {
          peopleType: 'Followers'
        }
      })
      .state('userProfile.helped', {
        url: '/helped',
        templateUrl: 'userProfile/userProfileKeeps.tpl.html',
        controller: 'UserProfileKeepsCtrl'
      })

    // ↓↓↓↓↓ Important: This needs to be last! ↓↓↓↓↓
    .state('library', {
      url: '/:username/:librarySlug',
      templateUrl: 'libraries/library.tpl.html',
      controller: 'LibraryCtrl',
      data: {
        librarySearch: false
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
        controller: 'SearchCtrl',
        data: {
          librarySearch: true
        }
      });
    // ↑↑↑↑↑ Important: This needs to be last! ↑↑↑↑↑
});
