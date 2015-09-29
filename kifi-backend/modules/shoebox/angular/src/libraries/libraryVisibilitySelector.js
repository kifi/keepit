'use strict';

angular.module('kifi')

.directive('kfLibraryVisibilitySelector', [
  'profileService', 'ORG_PERMISSION', 'ORG_SETTING_VALUE',
  function (profileService, ORG_PERMISSION, ORG_SETTING_VALUE) {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'libraries/libraryVisibilitySelector.tpl.html',
      scope: {
        library: '=',
        space: '=?'
      },
      link: function ($scope) {
        $scope.ORG_PERMISSION = ORG_PERMISSION;
        $scope.ORG_SETTING_VALUE = ORG_SETTING_VALUE;

        // This option is temporarily treated as a boolean in the UI, although it probably won't be
        // in the near future.
        $scope.orgMemberAccessWrite = $scope.library.orgMemberAccess === 'read_write' ? true : false;

        $scope.isUserOrgMember = (profileService.me.orgs.length > 0);

        $scope.changeOrgMemberAccess = function() {
          // This gets sent to the backend
          $scope.library.orgMemberAccess = $scope.library.orgMemberAccess === 'read_write' ? 'read_only' : 'read_write';
          // This binds the UI.
          $scope.orgMemberAccessWrite = !$scope.orgMemberAccessWrite;
        };

        $scope.onClickUpsellPublic = function () {

        };

        $scope.onHoverUpsellPublic = function () {

        };

        $scope.spaceIsOrg = function (space) {
          return !!space && !('firstName' in space);
        };

        $scope.$watch('space', function () {
          var viewer = $scope.space && $scope.space.viewer;

          // Unset public permissions when moving from an org where we're allowed to publish to one where we are not
          if (viewer && viewer.permissions.indexOf(ORG_PERMISSION.PUBLISH_LIBRARIES) === -1 && $scope.library.visibility === 'published') {
            $scope.library.visibility = 'organization';
          }
        });
      }
    };
  }
]);
