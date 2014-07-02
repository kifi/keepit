'use strict';

angular.module('kifi.layout.nav', ['util'])

.directive('kfNav', [
  '$location', 'util', 'keepService', 'friendService', 'tagService',
  function ($location, util, keepService, friendService, tagService) {
    return {
      //replace: true,
      restrict: 'A',
      templateUrl: 'layout/nav/nav.tpl.html',
      link: function (scope /*, element, attrs*/ ) {
        scope.counts = {
          keepCount: keepService.totalKeepCount,
          friendsCount: friendService.totalFriends(),
          friendsNotifCount: friendService.requests.length
        };

        scope.$watch(function () {
          return friendService.requests.length;
        }, function (value) {
          scope.counts.friendsNotifCount = value;
        });

        scope.$watch(friendService.totalFriends, function (value) {
          scope.counts.friendsCount = value;
        });

        scope.$watch(tagService.getTotalKeepCount, function (val) {
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
