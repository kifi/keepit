'use strict';

angular.module('kifi')

.directive('kfOrganizationMember', [
  function () {
    return {
      restrict: 'A',
      templateUrl: 'orgProfile/orgProfileMember.tpl.html',
      $scope: {
        member: '=',
        me: '='
      },
      replace: true,
      link: function ($scope) {
        $scope.$on('memberOpened', function (e, openedMember) {
          if ($scope.member !== openedMember) {
            close();
          }
        });

        var _controlsOpen = false;

        function open() {
          _controlsOpen = true;
          $scope.$emit('openedMember', $scope.member);
        }

        function close() {
          _controlsOpen = false;
        }

        $scope.isOpen = function () {
          return _controlsOpen;
        };

        $scope.toggleControls = function () {
          if (_controlsOpen) {
            close();
          } else {
            open();
          }
        };

        $scope.hasAcceptedInvitation = function () {
          return !$scope.member.lastInvitedAt;
        };

        $scope.shouldShowMakeAdmin = function () {
          return $scope.me.role === 'owner' && $scope.member.role !== 'owner' && $scope.hasAcceptedInvitation();
        };

        $scope.shouldShowRemove = function () {
          return $scope.me.role === 'owner';
        };

        $scope.shouldShowInvite = function () {
          return !$scope.hasAcceptedInvitation();
        };

        $scope.triggerInvite = function () {
          $scope.$emit('inviteMember', $scope.member);
        };

        $scope.triggerRemove = function () {
          $scope.$emit('removeMember', $scope.member);
        };

        $scope.triggerMakeAdmin = function () {
          $scope.$emit('promoteMember', $scope.member);
        };
      }
    };
  }
]);
