'use strict';

angular.module('kifi')

.directive('kfLeftHandNav', [
  '$rootElement', '$rootScope', '$document', 'profileService', 'userProfileActionService', 'orgProfileService',
  function ($rootElement, $rootScope, $document, profileService, userProfileActionService, orgProfileService) {
    return {
      restrict: 'A',
      templateUrl: 'leftHandNav/leftHandNav.tpl.html',
      link: function (scope) {
        scope.navBarEnabled = profileService.hasExperiment('new_sidebar');
        scope.me = profileService.me;
        scope.libraries = [];
        scope.orgs = scope.me.orgs;

        var INITIAL_PAGE_SIZE = 3;
        var PAGE_SIZE = 10
        var extraLibraries = [];
        scope.fetchLibraries = function (pageNumber, pageSize) {
          var filter = 'own';
          scope.hasMoreUserLibaries = false;
          return userProfileActionService
              .getLibraries(scope.me.username, filter, pageNumber, pageSize + 1)
              .then(function (data) {
                scope.loaded = true;
                return data[filter];
              }).then(function(libs) {
                scope.hasMoreUserLibaries = libs.length == pageSize + 1;
                libs.splice(pageSize);
                if (pageNumber == 0) {
                  extraLibraries = libs.splice(INITIAL_PAGE_SIZE);
                  scope.libraries = scope.libraries.concat(libs);
                } else {
                  scope.libraries = scope.libraries.concat(extraLibraries).concat(libs);
                  extraLibraries = [];
                }
              });
        };

        scope.fetchLibraries(0, PAGE_SIZE);

        scope.fetchOrgLibraries = function (org, offset, limit) {
          org.hasMoreLibraries = false;
          orgProfileService.getOrgLibraries(org.id, offset, limit + 1)
            .then(function (data) {
              org.hasMoreLibraries = data.libraries.length == limit + 1;
              data.libraries.splice(limit);
              org.libraries = (org.libraries || []).concat(data.libraries);
            });
        }

        scope.orgs.forEach(function (org) {
           scope.fetchOrgLibraries(org, 0, INITIAL_PAGE_SIZE);
        });

        scope.viewMoreOwnLibraries = function () {
          scope.fetchLibraries(Math.ceil(scope.libraries.length / PAGE_SIZE), PAGE_SIZE)
        }

        scope.viewMoreOrgLibraries = function (org) {
          scope.fetchOrgLibraries(org, org.libraries.length, PAGE_SIZE)
        }
      }
    };
  }
]);
