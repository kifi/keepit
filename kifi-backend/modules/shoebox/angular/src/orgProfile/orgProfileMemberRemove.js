'use strict';

angular.module('kifi')

.directive('kfOrgProfileMemberRemove', [
  function () {
    return {
      restrict: 'A',
      require: '^kfModal',
      link: function (scope) {
        var modalData = scope.modalData;

        if (modalData.member.lastInvitedAt) {
          modalData.title = 'Cancel Invitation?';
          modalData.descriptions = [(
            'Canceling this invitation will prevent ' + (modalData.member.firstName || modalData.member.email) +
            ' from joining the organization. They will not be notified. You can re-invite them at any time.'
          )];
          modalData.actionText = 'Cancel Invite';
          modalData.action = 'cancel';
        } else if (modalData.isMe) {
          modalData.title = 'Leave ' + modalData.organization.name + '?';
          modalData.descriptions = [
            (
              'Continuing will remove your access to content that is only visible to ' + modalData.organization.name + ' members. ' +
              'You will still have access to ' + modalData.organization.name +
              '\'s public libraries and any content you have been explicitly invited to follow or collaborate on.'
            ),
            (
              'You can be invited back at any time.'
            )
          ];
          modalData.action = 'remove';
          modalData.actionText = 'Leave Organization';
        } else {
          modalData.title = 'Remove ' + modalData.member.firstName + ' from ' + modalData.organization.name + '?';
          modalData.descriptions = [
            (
              'Continuing will remove ' + modalData.member.firstName +
              '\'s access to content that is only visible to ' + modalData.organization.name + ' members. ' +
              'They will still have access to your organization\'s public libraries ' +
              'and any content they have been explicitly invited to follow or collaborate on.'
            ),
            (
              'They will not receive a notification and you can invite them back at any time.'
            )
          ];
          modalData.action = 'remove';
          modalData.actionText = 'Remove Member';
        }
      }
    };
  }
]);
