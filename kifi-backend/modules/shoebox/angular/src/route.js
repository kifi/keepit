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
      .when('/:handle/libraries', '/:handle')
      .otherwise('/');  // last resort

    // Set up the states.
    $stateProvider
      .state('home', {
        url: '/',
        controller: 'HomeCtrl',
        templateUrl: 'home/home.tpl.html',
        'abstract': true
      })
      .state('home.feed', {
        url: '',
        controller: 'FeedCtrl',
        templateUrl: 'feed/feed.tpl.html'
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
        url: '/settings',
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
      .state('userOrOrg', {
        url: '/:handle?authToken',
        controller: [
          '$state', '$stateParams', 'orgProfileService',
          function ($state, $stateParams, orgProfileService) {
            orgProfileService
              .userOrOrg($stateParams.handle, $stateParams.authToken)
              .then(function (userOrOrgData) {
                var type = userOrOrgData.type;
                if (type === 'user') {
                  $state.go('userProfile.libraries.own', $stateParams, { location: false });
                } else if (type === 'org') {
                  $state.go('orgProfile.libraries', $stateParams, { location: false });
                }
              });
          }
        ]
      })
      .state('orgProfile', {
        url: '/:handle?authToken',
        params: { organization: null },
        templateUrl: 'orgProfile/orgProfile.tpl.html',
        controller: 'OrgProfileCtrl',
        resolve: {
          profile: [
            '$state', '$stateParams','orgProfileService',
            function ($state, $stateParams, orgProfileService) {
              // return the Promise to make its value available to the controller
              return orgProfileService
                .userOrOrg($stateParams.handle, $stateParams.authToken)
                .then(function (userOrOrgData) {
                  var type = userOrOrgData.type;

                  if (type === 'org') { // sanity check
                    if (userOrOrgData.result && userOrOrgData.result.error) {
                      throw new Error(userOrOrgData.result.error);
                    } else {
                      // success
                      return userOrOrgData.result;
                    }
                  } else {
                    throw new Error('orgProfile state was given invalid type ' + type);
                  }
                });
            }
          ]
        },
        'abstract': true
      })
      .state('orgProfile.members', {
        url: '/members',
        controller: 'OrgProfileMemberManageCtrl',
        templateUrl: 'orgProfile/orgProfileMemberManage.tpl.html'
      })
      .state('orgProfile.libraries', {
        url: '',
        controller: 'OrgProfileLibrariesCtrl',
        templateUrl: 'orgProfile/orgProfileLibraries.tpl.html'
      })
      .state('organizations', {
        url: '/organizations'
      })
      .state('organizations.new', {
        url: '/new'
      })
      .state('userProfile', {
        url: '/:handle?authToken',
        templateUrl: 'userProfile/userProfile.tpl.html',
        controller: 'UserProfileCtrl',
        resolve: {
          profile: [
            '$state', '$stateParams', 'orgProfileService',
            function ($state, $stateParams, orgProfileService) {
              // return the Promise to make its value available to the controller
              return orgProfileService
                .userOrOrg($stateParams.handle, $stateParams.authToken)
                .then(function (userOrOrgData) {
                  var type = userOrOrgData.type;

                  if (type === 'user') { // sanity check
                    return userOrOrgData.result;
                  } else {
                    throw new Error('userProfile state was given invalid type ' + type);
                  }
                });
            }
          ]
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
        url: '/:handle/:librarySlug?authToken',
        templateUrl: 'libraries/library.tpl.html',
        controller: 'LibraryCtrl',
        resolve: {
          libraryService: 'libraryService',
          library: ['libraryService', '$stateParams', function (libraryService, $stateParams) {
            return libraryService.getLibraryByHandleAndSlug($stateParams.handle, $stateParams.librarySlug, $stateParams.authToken);
          }],
          libraryImageLoaded: ['$q', '$timeout', 'env', 'library', function ($q, $timeout, env, library) {
            if (library.image) {
              var deferred = $q.defer();
              var promise = loadImage($q, env.picBase + '/' + library.image.path).then(function () {
                deferred.resolve(true);
              }, function () {
                deferred.resolve(false);
              });
              $timeout(function () {
                deferred.resolve({promise: promise});
              }, 12);  // low number b/c it delays many library page loads
              return deferred.promise;
            }
            return false;
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

    function loadImage($q, url) {
      var deferred = $q.defer();
      var img = new Image();
      img.onload = function () {
        deferred.resolve(img);
      };
      img.onerror = function (e) {
        deferred.reject(e);
      };
      img.src = url;
      return deferred.promise;
    }
  }
]);
