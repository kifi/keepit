'use strict';

angular.module('kifi')

.directive('kfLeftHandNav', [
  '$rootElement', '$rootScope', '$document', 'profileService', 'userProfileActionService', 'orgProfileService',
  function ($rootElement, $rootScope, $document, profileService, userProfileActionService, orgProfileService) {
    return {
      restrict: 'A',
      templateUrl: 'leftHandNav/leftHandNav.tpl.html',
      link: function(scope) {

        scope.me = profileService.me;
        scope.libraries = [];
        scope.orgs = scope.me.orgs;

        scope.fetchLibraries = function(pageNumber, pageSize) {
          var filter = 'own';
          return userProfileActionService
              .getLibraries(scope.me.username, filter, pageNumber, pageSize)
              .then(function (data) {
                scope.loaded = true;
                return data[filter];
              }).then(function(libs) {
                scope.libraries = libs;
              });
        };

        scope.fetchLibraries(0, 3);

        scope.orgs.forEach(function(org) {
           orgProfileService.getOrgLibraries(org.id, 0, 3)
               .then(function(data) {
                  org.libraries = data.libraries;
               });
        });

      }
    };
  }
]);
