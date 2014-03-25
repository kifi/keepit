'use strict';

angular.module('kifi.friends', [
  'util',
  'kifi.profileService',
  'kifi.routeService',
  'kifi.inviteService' // for kfSocialInviteSearch
])

.config([
  '$routeProvider',
  function ($routeProvider) {
    $routeProvider.when('/friends', {
      templateUrl: 'friends/friends.tpl.html',
      controller: 'FriendsCtrl'
    });
  }
])

.controller('FriendsCtrl', [
  '$scope', '$window',
  function ($scope, $window) {
    $window.document.title = 'Kifi • Your Friends on Kifi';

    // plenty to do!
  }
])

.directive('kfSocialInviteSearch', [ // move to /invite/
  'inviteService',
  function (inviteService) {
    return {
      scope: {
        'friend': '&'
      },
      replace: true,
      restrict: 'A',
      templateUrl: 'friends/inviteSearch.tpl.html',
      link: function (scope/*, element, attrs*/) {
        scope.search = {};
        scope.search.showDropdown = false;

        scope.results = inviteService.inviteList;
        scope.selected = inviteService.socialSelected;

        scope.change = function (e) {
          inviteService.socialSearch(scope.search.name).then(function (res) {
            if (!res || res.length === 0) {
              scope.search.showDropdown = false;
            } else {
              scope.search.showDropdown = true;
            }
          });
        };

        // scope.focus = function (e) {
        //   console.log('focus', e);
        // };

        scope.blur = function (e) {
          console.log('blur', e);
        };

      }
    };
  }
]);

