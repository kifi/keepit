'use strict';

angular.module('kifi.invite', [
  'util',
  'kifi.profileService',
  'kifi.routeService',
  'jun.facebook'
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
  '$scope', '$http', 'profileService', 'routeService', '$window',
  function ($scope, $http, profileService, routeService, $window) {

    $window.document.title = 'Kifi • Invite your friends';

    // bogus data just to get everyone started
    var friend = {
      image: 'https://graph.facebook.com/71105121/picture?width=75&height=75',
      label: 'Andrew Conner',
      status: 'joined',
      value: 'facebook/71105121'
    };
    $scope.friends = [friend];

    $scope.whoToInvite = [1,2,3,4,5,6,7,8,9,10,11,12,13,14]; //data is not currently used, just need some number of elements

    $scope.invite = function (friend) {
      // `value` will let you decide what platform the user is coming from. Perhaps better to let inviteService decide?
      // is 'friend' overloaded naming wise? is socialFriend better?
      $window.alert('Inviting ' + friend);
    };

  }
]);
