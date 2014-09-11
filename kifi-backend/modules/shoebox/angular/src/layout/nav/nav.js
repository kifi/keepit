'use strict';

angular.module('kifi')

.directive('kfNav', [
  '$location', 'util', 'friendService', 'tagService', 'profileService', 'libraryService', '$rootScope',
  function ($location, util, friendService, tagService, profileService, libraryService, $rootScope) {
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

        scope.addLibrary = function () {
          $rootScope.$emit('showGlobalModal', 'createLibrary');
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

        scope.inRecoExperiment = function () {
          return profileService.me && profileService.me.experiments && profileService.me.experiments.indexOf('recos_beta') >= 0;
        };
      }
    };
  }
]);
