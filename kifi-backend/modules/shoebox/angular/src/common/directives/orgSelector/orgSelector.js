'use strict';

angular.module('kifi')

.directive('kfOrgSelector', [
  'profileService', 'LIB_PERMISSION', 'ORG_PERMISSION', 'ORG_SETTING_VALUE', 'orgProfileService',
  function(profileService, LIB_PERMISSION, ORG_PERMISSION, ORG_SETTING_VALUE, orgProfileService) {
    return {
      restrict: 'A',
      templateUrl: 'common/directives/orgSelector/orgSelector.tpl.html',
      scope: {
        libraryProps: '=',
        library: '=',
        space: '=?'
      },
      link: function ($scope) {
        $scope.ORG_PERMISSION = ORG_PERMISSION;
        $scope.LIB_PERMISSION = LIB_PERMISSION;
        $scope.ORG_SETTING_VALUE = ORG_SETTING_VALUE;
        $scope.me = profileService.me;
        $scope.space = $scope.space || {};
        $scope.libraryOwner = $scope.library.owner || $scope.me;

        $scope.unsetOrg = function () {
          // Allow moving if...
          if (!$scope.library.id || // we're creating a new library
              $scope.hasPermission(LIB_PERMISSION.MOVE_LIBRARY)) { // or we have permission to move the existing library
            $scope.libraryProps.selectedOrgId = undefined;
            $scope.space.destination = $scope.library.owner;
          }
        };

        $scope.setOrg = function (id) {
          if (!$scope.library.id ||
              $scope.hasPermission(LIB_PERMISSION.MOVE_LIBRARY)) {
            // Give preference to (1) id from args, (2) current page, (3) First organization in list.
            var orgId = id || ($scope.library.org || $scope.me.orgs[0]).id;
            $scope.libraryProps.selectedOrgId = orgId;
            $scope.space.destination = $scope.me.orgs.filter(function(org) {
              return org.id === orgId;
            })[0];
          }
        };

        $scope.orgs = function () {
          if (!$scope.library.owner || $scope.library.owner.id === $scope.me.id) {
            return $scope.me.orgs;
          } else {
            return [$scope.library.org];
          }
        };

        $scope.spaceIsOrg = function (space) {
          return !!space && !('firstName' in space);
        };

        // Store privacy setting when switching between org and user profile
        var privacyToken = {
          org: $scope.library.visibility
        };

        $scope.$watch('space.destination', function (newValue, oldValue) {
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

        $scope.hasPermission = function (permission) {
          return $scope.library.permissions.indexOf(permission) !== -1;
        };

        $scope.onClickUpsellRelocate = function () {
          orgProfileService.trackEvent('user_clicked_page', $scope.space.destination, { action: 'clickSelectorUpsell' });
        };

        $scope.onHoverUpsellRelocate = function () {
          orgProfileService.trackEvent('user_viewed_page', $scope.space.destination, { action: 'viewSelectorUpsell' });
        };
      }
    };
  }
]);
