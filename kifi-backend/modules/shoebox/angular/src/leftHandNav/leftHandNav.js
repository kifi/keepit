'use strict';

angular.module('kifi')

.directive('kfLeftHandNav', [
  '$rootElement', '$rootScope', '$document', '$q', 'profileService', 'userProfileActionService', 'orgProfileService',
  function ($rootElement, $rootScope, $document, $q, profileService, userProfileActionService, orgProfileService) {
    return {
      restrict: 'A',
      templateUrl: 'leftHandNav/leftHandNav.tpl.html',
      link: function (scope) {
        scope.me = profileService.me;
        scope.libraries = [];
        scope.orgs = scope.me.orgs;
        // TODO: REMOVE THIS HACK
        document.body.style.overflow = 'hidden';

        var INITIAL_PAGE_SIZE = 3;
        var PAGE_SIZE = 10;
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
                scope.hasMoreUserLibaries = libs.length === pageSize + 1;
                libs.splice(pageSize);
                if (pageNumber === 0) {
                  extraLibraries = libs.splice(INITIAL_PAGE_SIZE);
                  scope.libraries = scope.libraries.concat(libs);
                } else {
                  scope.libraries = scope.libraries.concat(extraLibraries).concat(libs);
                  extraLibraries = [];
                }
              });
        };

        var promises = [];
        promises.push(scope.fetchLibraries(0, PAGE_SIZE));

        scope.fetchOrgLibraries = function (org, offset, limit) {
          org.hasMoreLibraries = false;
          return orgProfileService.getOrgLibraries(org.id, offset, limit + 1)
            .then(function (data) {
              org.hasMoreLibraries = data.libraries.length === limit + 1;
              data.libraries.splice(limit);
              org.libraries = (org.libraries || []).concat(data.libraries);
            });
        };

        scope.orgs.forEach(function (org) {
           promises.push(scope.fetchOrgLibraries(org, 0, INITIAL_PAGE_SIZE));
        });

        scope.showUserAndOrgContent = false;
        $q.all(promises).then(function() {
          scope.showUserAndOrgContent = true;
        });

        scope.fetchingUserLibraries = false;
        scope.viewMoreOwnLibraries = function () {
          scope.fetchingUserLibraries = true;
          scope.fetchLibraries(Math.ceil(scope.libraries.length / PAGE_SIZE), PAGE_SIZE).then(function() {
            scope.fetchingUserLibraries = false;
          });
        };

        scope.viewMoreOrgLibraries = function (org) {
          org.fetchingLibraries = true;
          scope.fetchOrgLibraries(org, org.libraries.length, PAGE_SIZE).then(function() {
            org.fetchingLibraries = false;
          });
        };
      }
    };
  }
]);
