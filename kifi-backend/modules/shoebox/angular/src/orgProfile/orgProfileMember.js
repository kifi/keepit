'use strict';

angular.module('kifi')

.directive('kfOrgMember', [
  function () {
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

    function _shouldShowPromote() {
      var $scope = this;
      return (
        $scope.me.role === 'admin' &&
        $scope.member.role !== 'admin' &&
        $scope.hasAcceptedInvite()
      );
    }

    function _shouldShowDemote() {
      var $scope = this;
      return (
        $scope.me.id === $scope.organization.ownerId &&
        $scope.member.role !== 'member' &&
        $scope.hasAcceptedInvite()
      );
    }

    function _shouldShowRemove() {
      var $scope = this;
      var hasCorrectPermission = ($scope.me.role === 'admin' && $scope.member.role !== 'admin') || ($scope.me.id === $scope.profile.ownerId);
      return $scope.hasAcceptedInvite() && (hasCorrectPermission || $scope.isMe());
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
        promise.then(function () {
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

    function _triggerPromote() {
      var $scope = this;
      $scope.$emit('promoteMember', $scope.member);
    }

    function _triggerDemote() {
      var $scope = this;
      $scope.$emit('demoteMember', $scope.member);
    }


    return {
      restrict: 'A',
      templateUrl: 'orgProfile/orgProfileMember.tpl.html',
      $scope: {
        member: '=',
        me: '=',
        organization: '='
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

        $scope.open = function() {
          $scope.controlsOpen = true;
          $scope.$emit('openedMember', $scope.member);
        };

        $scope.close = function() {
          $scope.controlsOpen = false;
          $scope.resentInvite = false;
        };

        $scope.isOpen = function() {
          return $scope.controlsOpen;
        };

        $scope.toggleControls = function() {
          if ($scope.controlsOpen) {
            $scope.close();
          } else {
            $scope.open();
          }
        };

        $scope.isMe = _isMe;

        $scope.resentInvite = _resentInvite;

        $scope.hasAcceptedInvite = _hasAcceptedInvite;
        $scope.shouldShowPromote = _shouldShowPromote;
        $scope.shouldShowDemote = _shouldShowDemote;
        $scope.shouldShowRemove = _shouldShowRemove;
        $scope.shouldShowInvite = _shouldShowInvite;
        $scope.shouldShowAcceptInvite = _shouldShowAcceptInvite;
        $scope.triggerInvite = _triggerInvite;
        $scope.triggerCancelInvite = _triggerCancelInvite;
        $scope.triggerRemove = _triggerRemove;
        $scope.triggerPromote = _triggerPromote;
        $scope.triggerDemote = _triggerDemote;
      }
    };
  }
]);
