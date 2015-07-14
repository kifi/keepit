'use strict';

angular.module('kifi')

.directive('kfOrganizationMember', [
  function () {
    return {
      restrict: 'A',
      templateUrl: 'orgProfile/orgProfileMember.tpl.html',
      scope: {
        member: '='
      },
      replace: true,
      link: function (scope) {
        var member = scope.member;
        member.role = member.role || 'Member';

        scope.isOwner = function () {
          return member.role === 'Owner';
        };

        scope.hiddenStyle = { 'display': 'none' };

        var controlsOpen = false;

        scope.isOpen = function () {
          return controlsOpen;
        };

        scope.toggleControls = function () {
          controlsOpen = !controlsOpen;
        };

        var _cachedMembershipActionText = null;

        scope.membershipActionText = function () {
          if (!_cachedMembershipActionText) {

            var myUsername = 'stephen';
            // var myUsername = profileService.me.username;

            if (member.username === myUsername) {
              _cachedMembershipActionText = 'leave organization';
            } else if (!member.verified){
              _cachedMembershipActionText = 'resend invite';
            } else if (scope.$parent.profile.members[0].username === myUsername) {
              _cachedMembershipActionText = 'remove member';
            }else {
              _cachedMembershipActionText = '';
            }
          }

          return _cachedMembershipActionText;
        };

        scope.showMembershipAction = function () {
          return !!scope.membershipActionText();
        };

        scope.triggerMembershipAction = function () {
          if (scope.membershipActionText() === 'resend invite') {
            //alert('Resent invite');
            return;
          }

          if (scope.$parent.removeMember) {
            scope.$parent.removeMember(member);
          }
        };

        scope.triggerMakeAdmin = function () {
          member.role = 'Owner';
        };

        scope.showMakeAdmin = function () {
          return !scope.isOwner() && member.verified;
        };
      }
    };
  }
]);
