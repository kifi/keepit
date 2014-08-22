'use strict';

angular.module('kifi')

.directive('kfNav', [
  '$location', 'util', 'keepService', 'friendService', 'tagService', 'profileService' /* only needed for libraries experiment */, 'libraryService',
  function ($location, util, keepService, friendService, tagService, profileService, libraryService) {
    return {
      //replace: true,
      restrict: 'A',
      templateUrl: 'layout/nav/nav.tpl.html',
      link: function (scope /*, element, attrs*/ ) {
        scope.counts = {
          friendsCount: friendService.totalFriends(),
          friendsNotifCount: friendService.requests.length
        };

        scope.librariesEnabled = false;
        scope.libraries = [];
        scope.invited = [];

        scope.$watch(function () {
          return libraryService.isAllowed();
        }, function (n) {
          scope.librariesEnabled = n || false;
          if (scope.librariesEnabled) {
            libraryService.fetchLibrarySummaries().then(function (summaries) {
              scope.libraries = summaries.libraries;
              scope.invited = summaries.invited;
            });
          }
        });

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
