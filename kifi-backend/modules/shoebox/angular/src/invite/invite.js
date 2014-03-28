'use strict';

angular.module('kifi.invite', [
  'util',
  'kifi.profileService',
  'kifi.routeService',
  'jun.facebook',
  'kifi.inviteService'
])

.config([
  '$routeProvider',
  function ($routeProvider) {
    $routeProvider.when('/invite', {
      templateUrl: 'invite/invite.tpl.html',
      controller: 'InviteCtrl'
    });
  }
])

.controller('InviteCtrl', [
  '$scope', '$http', 'profileService', 'routeService', '$window', 'wtiService',
  function ($scope, $http, profileService, routeService, $window, wtiService) {
    $window.document.title = 'Kifi • Invite your friends';

    // bogus data just to get everyone started
    var friend = {
      image: 'https://graph.facebook.com/71105121/picture?width=75&height=75',
      label: 'Andrew Conner',
      status: 'joined',
      value: 'facebook/71105121'
    };
    $scope.friends = [friend];

    wtiService.loadInitial();
    $scope.whoToInvite = wtiService.list;

    $scope.wtiScrollDistance = '100%';
    $scope.isWTIScrollDisabled = function () {
      return !wtiService.hasMore();
    };
    $scope.wtiScrollNext = wtiService.getMore;


    $scope.invite = function (friend) {
      // `value` will let you decide what platform the user is coming from. Perhaps better to let inviteService decide?
      // is 'friend' overloaded naming wise? is socialFriend better?
      $window.alert('Inviting ' + friend);
    };

  }
])

.directive('kfSocialInviteWell', [
  'profileService',
  function (profileService) {
    return {
      scope: {},
      replace: true,
      restrict: 'A',
      templateUrl: 'invite/inviteWell.tpl.html',
      link: function (scope/*, element, attrs*/) {
        scope.networks = profileService.networks;

        profileService.getNetworks();
      }
    }
  }
])

.directive('kfSocialInviteSearch', [
  'inviteService', '$document',
  function (inviteService, $document) {
    return {
      scope: {},
      replace: true,
      restrict: 'A',
      templateUrl: 'invite/inviteSearch.tpl.html',
      link: function (scope, element/*, attrs*/) {
        scope.search = {};
        scope.search.showDropdown = false;

        scope.results = inviteService.inviteList;
        scope.selected = inviteService.socialSelected;

        scope.change = _.debounce(function (e) { // todo: integrate debounce into Clutch, remove me
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
          console.log('this person:', result);
        }

        scope.$on('$destroy', function () {
          $document.off('click', clickOutside);
        });

        $document.on('click', clickOutside);

        scope.blur = function (e) {
          return true;
        };

      }
    };
  }
]);
