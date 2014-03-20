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
      scope.mainImage = friend.image;
      scope.mainLabel = friend.name;
      scope.bylineIcon = '/img/networks2.png';
      scope.hidden = false;

      scope.facebook = friend.network === 'facebook';
      scope.linkedin = friend.network === 'linkedin';
      scope.email    = friend.network === 'email';

      scope.action = function () {
        $window.alert('Inviting: ' + friend.name);
      };
      scope.closeAction = function () {
        scope.hidden = true;
      };
      if (friend.invited) {
        scope.invited = true;
        scope.actionText = 'Resend';
        scope.byline = 'Invited ' + friend.invitedHowLongAgo + ' days ago';
      } else {
        scope.invited = false;
        scope.byline = 'A friend on ' + friend.network.charAt(0).toUpperCase() + friend.network.slice(1);
        scope.actionText = 'Add';
      }
    }
  };
}]);
