'use strict';

angular.module('kifi.layout.nav', ['util'])

.directive('kfNav', [
  '$location', 'util', 'keepService',
  function ($location, util, keepService) {
    return {
      //replace: true,
      restrict: 'A',
      templateUrl: 'layout/nav/nav.tpl.html',
      link: function (scope /*, element, attrs*/ ) {
        scope.counts = {
          keepCount: keepService.totalKeepCount,
          friendsNotifCount: 0
        };

        scope.$watch(function () {
          return keepService.totalKeepCount;
        }, function (val) {
          scope.counts.keepCount = val;
        });

        scope.isActive = function (path) {
          var loc = $location.path();
          return loc === path || util.startsWith(loc, path + '/');
        };
      }
    };
  }
]);
