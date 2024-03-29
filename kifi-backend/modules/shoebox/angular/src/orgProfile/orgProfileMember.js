'use strict';

angular.module('kifi')

.directive('kfOrgMember', [
  'profileService', 'modalService',
  function (profileService, modalService) {

    function _isMe() {
      var $scope = this;
      return $scope.member.id === profileService.me.id;
    }

    function _isMeAndOwner() {
      var $scope = this;

      return (
        profileService.me.id === $scope.organization.ownerId &&
        profileService.me.id === $scope.member.id
      );
    }

    function _getResentInvite() {
      var $scope = this;
      return $scope.model.resentInvite;
    }

    function _hasAcceptedInvite() {
      var $scope = this;
      return !$scope.member.lastInvitedAt;
    }

    function _shouldShowRole() {
      var $scope = this;

      return $scope.thisViewer.membership && $scope.thisViewer.membership.role;
    }

    function _shouldShowMakeOwner() {
      var $scope = this;

      return (
        $scope.organization.ownerId === $scope.me.id &&
        $scope.organization.ownerId !== $scope.member.id &&
        $scope.hasAcceptedInvite()
      );
    }

    function _shouldShowPromote() {
      var $scope = this;

      return (
        ($scope.thisViewer.membership && $scope.thisViewer.membership.role === 'admin') &&
        $scope.member.role !== 'admin' &&
        $scope.hasAcceptedInvite()
      );
    }

    function _shouldShowDemote() {
      var $scope = this;

      return (
        !$scope.isMeAndOwner() &&
        profileService.me.id === $scope.organization.ownerId &&
        $scope.member.role !== 'member' &&
        $scope.hasAcceptedInvite()
      );
    }

    function _shouldShowRemove() {
      var $scope = this;
      var hasCorrectPermission = (
        (
          ($scope.thisViewer.membership && $scope.thisViewer.membership.role === 'admin') &&
          $scope.member.role !== 'admin'
        ) || (
          profileService.me.id === $scope.profile.ownerId
        )
      );

      return (
        !$scope.isMeAndOwner() &&
        $scope.hasAcceptedInvite() &&
        ($scope.isMe() || hasCorrectPermission)
      );
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
          $scope.model.resentInvite = true;
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

    function _triggerMakeOwner() {
      var $scope = this;

      var opts = {
        organization: $scope.organization,
        member: $scope.member,
        returnAction: function () {
          $scope.$emit('resetAndFetch');
          $scope.organization.ownerId = $scope.member.id;
        }
      };
      for (var i=0, len=$scope.members.length; i < len; i++) {
        if ($scope.members[i].id === $scope.organization.ownerId) {
          opts.currentOwner = $scope.members[i];
        }
      }
      modalService.open({
        // TODO: Template name needs to be longer
        template: 'orgProfile/orgProfileMemberOwnerTransferModal.tpl.html',
        modalData: opts
      });
    }

    function _triggerPromote() {
      var $scope = this;
      $scope.$emit('promoteMember', $scope.member);
    }

    function _triggerDemote() {
      var $scope = this;
      $scope.$emit('demoteMember', $scope.member);
    }

    function _triggerClickedAvatar() {
      var $scope = this;
      $scope.$emit('clickedAvatar', $scope.member);
    }

    function _role() {
      var $scope = this;
      return ($scope.hasAcceptedInvite() ? '' : 'Pending ') +
        ($scope.organization.ownerId === $scope.member.id ? 'Owner' : ($scope.member.role === 'admin' ? 'Admin' : 'Member'));
    }

    return {
      restrict: 'A',
      templateUrl: 'orgProfile/orgProfileMember.tpl.html',
      $scope: {
        member: '=',
        thisViewer: '=',
        organization: '='
      },
      replace: true,
      link: function ($scope) {
        $scope.$on('memberOpened', function (e, openedMember) {
          if ($scope.member !== openedMember) {
            $scope.close();
          }
        });

        $scope.model = { resentInvite: false }; // assign primitive to reference
        $scope._controlsOpen = false;

        $scope.open = function() {
          $scope.controlsOpen = true;
          $scope.$emit('toggledMember', $scope.member, $scope.controlsOpen);
        };

        $scope.close = function() {
          if ($scope.controlsOpen) {
            $scope.controlsOpen = false;
            $scope.model.resentInvite = false;
            $scope.$emit('toggledMember', $scope.member, $scope.controlsOpen);
          }
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
        $scope.isMeAndOwner = _isMeAndOwner;

        $scope.getResentInvite = _getResentInvite;

        $scope.shouldShowRole = _shouldShowRole;
        $scope.hasAcceptedInvite = _hasAcceptedInvite;
        $scope.shouldShowMakeOwner = _shouldShowMakeOwner;
        $scope.shouldShowPromote = _shouldShowPromote;
        $scope.shouldShowDemote = _shouldShowDemote;
        $scope.shouldShowRemove = _shouldShowRemove;
        $scope.shouldShowInvite = _shouldShowInvite;
        $scope.shouldShowAcceptInvite = _shouldShowAcceptInvite;
        $scope.triggerClickedAvatar = _triggerClickedAvatar;
        $scope.triggerInvite = _triggerInvite;
        $scope.triggerCancelInvite = _triggerCancelInvite;
        $scope.triggerRemove = _triggerRemove;
        $scope.triggerMakeOwner = _triggerMakeOwner;
        $scope.triggerPromote = _triggerPromote;
        $scope.triggerDemote = _triggerDemote;
        $scope.role = _role;
      }
    };
  }
]);
