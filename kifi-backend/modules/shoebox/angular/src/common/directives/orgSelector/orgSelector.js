'use strict';

angular.module('kifi')

.directive('kfOrgSelector', ['profileService',
  function(profileService) {
    return {
      restrict: 'A',
      templateUrl: 'common/directives/orgSelector/orgSelector.tpl.html',
      scope: { 
        libraryProps: '=',
        library: '=',
        space: '='
      },
      link: function($scope) {
        $scope.me = profileService.me;
        $scope.space = $scope.space || {};

        $scope.unsetOrg = function() {
          $scope.libraryProps.selectedOrgId = undefined;
          $scope.space.destination = $scope.me;
          onChangeSpace();
        };

        $scope.setOrg = function(id) { 
          // Give preference to (1) id from args, (2) current page, (3) First organization in list.
          var orgId = id || ($scope.library.org || $scope.me.orgs[0]).id;
          $scope.libraryProps.selectedOrgId = orgId;
          $scope.space.destination = $scope.me.orgs.filter(function(org) {
            return org.id === orgId;
          })[0];
          onChangeSpace();
        };

        $scope.spaceIsOrg = function (space) {
          return !('firstName' in (space || {}));
        };

        var onChangeSpace = function () {
          var currIsOrg = $scope.spaceIsOrg($scope.space.current);
          var destIsOrg = $scope.spaceIsOrg($scope.space.destination);

          if (currIsOrg !== destIsOrg) {
            if (currIsOrg) {
              if ($scope.library.visibility === 'organization') {
                $scope.library.visibility = 'secret';
              }
            } else {
              if ($scope.library.visibility === 'secret') {
                $scope.library.visibility = 'organization';
              }
            }
          }

          // Prevent non-org lib from having visibility === 'organization'
          if (!destIsOrg && $scope.library.visibility === 'organization') {
            $scope.library.visibility = 'secret';
          }
        };
      }
    };
  }
]);
