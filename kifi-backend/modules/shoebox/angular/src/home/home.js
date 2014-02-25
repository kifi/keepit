'use strict';

angular.module('kifi.home', ['util', 'kifi.keepService'])

.config([
  '$routeProvider',
  function ($routeProvider) {
    $routeProvider
    .when('/', {
      templateUrl: 'home/home.tpl.html',
      controller: 'HomeCtrl'
    });
  }
])

.controller('HomeCtrl', [
  '$scope', 'keepService',
  function ($scope, keepService) {
    $scope.page = {
      title: 'Browse your Keeps'
    };

    $scope.getSubtitle = function () {
      var numShown = $scope.results.numShown;
      switch (numShown) {
      case 0:
        return 'You have no Keeps';
      case 1:
        return 'Showing your only Keep';
      case 2:
        return 'Showing both of your Keeps';
      default:
        if (numShown === $scope.results.numTotal) {
          return 'Showing all ' + numShown + ' of your Keeps';
        }
        return 'Showing your ' + numShown + ' latest Keeps';
      }
    };
  }
]);
