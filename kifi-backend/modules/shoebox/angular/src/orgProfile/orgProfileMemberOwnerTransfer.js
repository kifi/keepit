'use strict';

angular.module('kifi')

.directive('kfOrgMemberOwnerTransfer', [
  'profileService', 'orgProfileService',
  function (profileService, orgProfileService) {
    return {
      restrict: 'A',
      require: '^kfModal',
      templateUrl: 'orgProfile/orgProfileMemberOwnerTransfer.tpl.html',
      link: function ($scope, element, attrs, kfModalCtrl) {
        $scope.transferOwner = function() {
          orgProfileService.transferOrgMemberOwnership($scope.modalData.organization.id, {
            newOwner: $scope.modalData.member.id
          }).then(function(response) {
            $scope.close();
            $scope.modalData.returnAction(response);
          });
        };

        $scope.close = function() {
          kfModalCtrl.close();
        };

        $scope.currentOwner = $scope.modalData.currentOwner;
        $scope.member = $scope.modalData.member;
      }
    };
  }
]);
