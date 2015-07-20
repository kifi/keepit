'use strict';

angular.module('kifi')

.directive('kfOrganizationMember', [
  function () {

    function _open() {
      var $scope = this;
      $scope._controlsOpen = true;
      $scope.$emit('openedMember', $scope.member);
    }

    function _close() {
      var $scope = this;
      $scope._controlsOpen = false;
      $scope._resentInvite = false;
    }

    function _isOpen() {
      var $scope = this;
      return $scope._controlsOpen;
    }

    function _toggleControls() {
      var $scope = this;
      if ($scope._controlsOpen) {
        $scope.close();
      } else {
        $scope.open();
      }
    }

    function _isMe() {
      var $scope = this;
      return $scope.member.id === $scope.me.id;
    }

    function _resentInvite() {
      var $scope = this;
      return $scope._resentInvite;
    }

    function _hasAcceptedInvite() {
      var $scope = this;
      return !$scope.member.lastInvitedAt;
    }

    function _shouldShowMakeAdmin() {
      var $scope = this;
      return $scope.me.role === 'owner' && $scope.member.role !== 'owner' && $scope.hasAcceptedInvite();
    }

    function _shouldShowRemove() {
      var $scope = this;
      return $scope.hasAcceptedInvite() && ($scope.me.role === 'owner' || $scope.member.id === $scope.me.id);
    }

    function _shouldShowInvite() {
      var $scope = this;
      return !$scope.isMe() && !$scope.hasAcceptedInvite();
    }

    function _shouldShowAcceptInvite() {
      var $scope = this;
      return $scope.isMe() && !$scope.hasAcceptedInvite();
    }

    function _triggerInvite() {
      var $scope = this;
      $scope.$emit('inviteMember', $scope.member, function (promise) {
        promise.then(function (response) {
          // TODO: WHY $scope.$parent??? Is it because ng-if creates a new scope?
          $scope.$parent._resentInvite = true;
        });
      });
    }

    function _triggerCancelInvite() {
      var $scope = this;
      $scope.$emit('cancelInvite', $scope.member);
    }

    function _triggerRemove() {
      var $scope = this;
      $scope.$emit('removeMember', $scope.member);
    }

    function _triggerMakeAdmin() {
      var $scope = this;
      $scope.$emit('promoteMember', $scope.member);
    }

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
            $scope.close();
          }
        });

        $scope._resentInvite = false;
        $scope._controlsOpen = false;

        $scope.open = _open;
        $scope.close = _close;
        $scope.isOpen = _isOpen;
        $scope.toggleControls = _toggleControls;

        $scope.isMe = _isMe;

        $scope.resentInvite = _resentInvite;

        $scope.hasAcceptedInvite = _hasAcceptedInvite;
        $scope.shouldShowMakeAdmin = _shouldShowMakeAdmin;
        $scope.shouldShowRemove = _shouldShowRemove;
        $scope.shouldShowInvite = _shouldShowInvite;
        $scope.shouldShowAcceptInvite = _shouldShowAcceptInvite;
        $scope.triggerInvite = _triggerInvite;
        $scope.triggerCancelInvite = _triggerCancelInvite;
        $scope.triggerRemove = _triggerRemove;
        $scope.triggerMakeAdmin = _triggerMakeAdmin;
      }
    };
  }
]);
