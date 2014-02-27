'use strict';

angular.module('kifi.profile', ['util', 'kifi.keepService'])

.config([
  '$routeProvider',
  function ($routeProvider) {
    $routeProvider
    .when('/profile', {
      templateUrl: 'profile/profile.tpl.html',
      controller: 'ProfileCtrl'
    });
  }
])

.controller('ProfileCtrl', [
  '$scope', 'keepService',
  function ($scope, keepService) {

  }
]);
