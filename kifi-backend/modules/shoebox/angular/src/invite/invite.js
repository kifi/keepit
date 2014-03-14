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

  }
]);
