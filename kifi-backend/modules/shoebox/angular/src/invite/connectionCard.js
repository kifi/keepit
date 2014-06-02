'use strict';

angular.module('kifi.invite.connectionCard', ['angularMoment'])


.directive('kfConnectionCard', ['$window', '$http', 'routeService', 'inviteService', function ($window, $http, routeService, inviteService) {
  return {
    scope: {
      'friend': '&',
      'refreshScroll': '=',
      'showGenericInviteError': '=',
      'showLinkedinTokenExpiredModal': '=',
      'showLinkedinHitRateLimitModal': '='
    },
    replace: true,
    restrict: 'A',
    templateUrl: 'invite/connectionCard.tpl.html',
    link: function (scope/*, element, attrs*/) {
      var friend = scope.friend();
      var network = friend.fullSocialId.split('/')[0];
      var inNetworkId = friend.fullSocialId.split('/')[1];
      var invited = (friend.lastInvitedAt != null);

      if (friend.pictureUrl != null) {
        scope.mainImage = friend.pictureUrl;
      } else if (network === 'email') {
        scope.mainImage = '/img/email-icon.png';
      } else {
        scope.mainImage = 'https://www.kifi.com/assets/img/ghost.100.png';
      }

      scope.mainLabel = friend.name;
      scope.hidden = false;

      scope.facebook = network === 'facebook';
      scope.linkedin = network === 'linkedin';
      scope.email    = network === 'email';

      scope.action = function () {
        inviteService.invite(network, inNetworkId).then(function () {
          scope.invited = true;
          scope.actionText = 'Resend';
          var inviteText = 'Invited just now';
          if (network === 'email') {
            scope.byline = inNetworkId;
            scope.byline2 = inviteText;
          } else {
            scope.byline = inviteText;
          }
        }, function (err) {
          if (err === 'token_expired') {
            scope.showLinkedinTokenExpiredModal = true;
          } else if (err === 'hit_rate_limit_reached') {
            scope.showLinkedinHitRateLimitModal = true;
          } else {
            scope.showGenericInviteError = true;
          }
        });
      };
      scope.closeAction = function () {
        scope.hidden = true;
        var data = { 'fullSocialId' : friend.fullSocialId };
        $http.post(routeService.blockWtiConnection, data);
      };
      if (invited) {
        scope.invited = true;
        scope.actionText = 'Resend';
        var inviteText = 'Invited ' + $window.moment(new Date(friend.lastInvitedAt)).fromNow();
        if (network === 'email') {
          scope.byline = inNetworkId;
          scope.byline2 = inviteText;
        } else {
          scope.byline = inviteText;
        }
      } else {
        scope.invited = false;
        scope.byline = network === 'email' ? inNetworkId : network.charAt(0).toUpperCase() + network.slice(1);
        scope.actionText = 'Invite';
      }
    }
  };
}]);
