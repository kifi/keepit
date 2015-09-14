'use strict';

angular.module('kifi')

.directive('kfLibraryVisibilitySelector',
  function () {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'libraries/libraryVisibilitySelector.tpl.html',
      scope: {
        library: '=',
        space: '=?'
      },
      link: function ($scope) {
        $scope.spaceIsOrg = function () {
          return $scope.space && !('firstName' in $scope.space);
        };

        // This option is temporarily treated as a boolean in the UI, although it probably won't be
        // in the near future.
        $scope.orgMemberAccessWrite = $scope.library.orgMemberAccess === 'read_write' ? true : false;

        $scope.changeOrgMemberAccess = function() {
          // This gets sent to the backend
          $scope.library.orgMemberAccess = $scope.library.orgMemberAccess === 'read_write' ? 'read_only' : 'read_write';
          // This binds the UI.
          $scope.orgMemberAccessWrite = !$scope.orgMemberAccessWrite;
        };
      }
    };
  }
);
