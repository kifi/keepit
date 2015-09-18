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
        space: '=?'
      },
      link: function($scope) {
        $scope.me = profileService.me;
        $scope.space = $scope.space || {};

        $scope.unsetOrg = function() {
          $scope.libraryProps.selectedOrgId = undefined;
          $scope.space.destination = $scope.me;
        };

        $scope.setOrg = function(id) {
          // Give preference to (1) id from args, (2) current page, (3) First organization in list.
          var orgId = id || ($scope.library.org || $scope.me.orgs[0]).id;
          $scope.libraryProps.selectedOrgId = orgId;
          $scope.space.destination = $scope.me.orgs.filter(function(org) {
            return org.id === orgId;
          })[0];
        };

        $scope.spaceIsOrg = function (space) {
          return !('firstName' in (space || {}));
        };

        // Store privacy setting when switching between org and user profile
        var privacyToken = {
          org: $scope.library.visibility
        };

        $scope.$watch('space.destination', function(newValue, oldValue) {
          var oldIsOrg = $scope.spaceIsOrg(oldValue);
          var newIsOrg = $scope.spaceIsOrg(newValue);

          if (oldIsOrg !== newIsOrg) {
            if (oldIsOrg) {
              if ($scope.library.visibility === 'organization') {
                $scope.library.visibility = 'secret';
                privacyToken.org = 'organization';
              } else {
                privacyToken.org = '';
              }
            } else {
              if (privacyToken.org === 'organization') {
                $scope.library.visibility = 'organization';
              }
            }
          }

          // Prevent non-org lib from having visibility === 'organization'
          if (!newIsOrg && $scope.library.visibility === 'organization') {
            $scope.library.visibility = 'secret';
          }
        });

        $scope.onClickUpsellRelocate = function () {

        };

        $scope.onHoverUpsellRelocate = function () {

        };
      }
    };
  }
]);
