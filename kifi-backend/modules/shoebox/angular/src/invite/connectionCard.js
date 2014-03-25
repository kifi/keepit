'use strict';

angular.module('kifi.invite.connectionCard', [])


.directive('kfConnectionCard', ['$window', function ($window) {
  return {
    scope: {
      'friend': '&'
    },
    replace: true,
    restrict: 'A',
    templateUrl: 'invite/connectionCard.tpl.html',
    link: function (scope/*, element, attrs*/) {
      var friend = scope.friend();
      var network = friend.fullSocialId.split('/')[0];
      var inNetworkId = friend.fullSocialId.split('/')[1];
      var invited = (friend.lastInvitedAt != null);
      var canInvite = friend.canBeInvited;

      scope.mainImage = friend.pictureUrl || "http://lorempixel.com/g/64/64/technics/Email";
      scope.mainLabel = friend.name;
      scope.hidden = false;

      scope.facebook = network === 'facebook';
      scope.linkedin = network === 'linkedin';
      scope.email    = network === 'email';

      scope.action = function () {
        $window.alert('Inviting: ' + friend.name);
      };
      scope.closeAction = function () {
        scope.hidden = true;
      };
      if (invited) {
        scope.invited = true;
        scope.actionText = 'Resend';
        scope.byline = 'Invited ' + friend.invitedHowLongAgo + ' days ago';
      } else {
        scope.invited = false;
        scope.byline = network==='email'?inNetworkId:'A friend on ' + network.charAt(0).toUpperCase() + network.slice(1);
        scope.actionText = 'Add';
      }
    }
  };
}]);
