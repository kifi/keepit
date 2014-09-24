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
        scope.mainLib = {};
        scope.secretLib = {};
        scope.userLibs = [];
        scope.invitedLibs = [];

        scope.$watch(function () {
          return libraryService.isAllowed();
        }, function (newVal) {
          scope.librariesEnabled = newVal;
          if (scope.librariesEnabled) {
            libraryService.fetchLibrarySummaries().then(function () {
              var libraries = libraryService.librarySummaries;
              scope.mainLib = _.find(libraries, function (lib) {
                  return lib.kind === 'system_main';
              });
              scope.secretLib = _.find(libraries, function (lib) {
                  return lib.kind === 'system_secret';
              });
              scope.userLibs = _.filter(libraries, function (lib) {
                return lib.kind === 'user_created';
              });
              scope.invitedLibs = libraryService.invitedSummaries;

              // TODO (aaron): get backend to provide 'numFollowers' field
              for (var i=0; i<scope.userLibs.length; i++) {
                scope.userLibs[i].numFollowers = 10;
              }
            });
          }
        });

        scope.addLibrary = function () {
          $rootScope.$emit('showGlobalModal', 'manageLibrary');
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
