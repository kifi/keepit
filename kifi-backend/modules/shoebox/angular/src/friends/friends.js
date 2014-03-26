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
  'inviteService', '$document',
  function (inviteService, $document) {
    return {
      scope: {
        'friend': '&'
      },
      replace: true,
      restrict: 'A',
      templateUrl: 'friends/inviteSearch.tpl.html',
      link: function (scope, element/*, attrs*/) {
        scope.search = {};
        scope.search.showDropdown = false;

        scope.results = inviteService.inviteList;
        scope.selected = inviteService.socialSelected;

        scope.change = _.debounce(function (e) {
          inviteService.socialSearch(scope.search.name).then(function (res) {
            if (!res || res.length === 0) {
              scope.search.showDropdown = false;
            } else {
              scope.search.showDropdown = true;
            }
          });
        }, 200);

        function clickOutside(e) {
          if (scope.search.showDropdown && !element.find(e.target)[0]) { // click was outside of dropdown
            scope.$apply(function () {
              scope.search.showDropdown = false;
            });
          }
        }

        scope.invite = function (result) {
          console.log('123', result);
        }

        scope.$on('$destroy', function () {
          $document.off('click', clickOutside);
        });

        $document.on('click', clickOutside);

        scope.blur = function (e) {
          console.log('blur', e);
        };

      }
    };
  }
]);

