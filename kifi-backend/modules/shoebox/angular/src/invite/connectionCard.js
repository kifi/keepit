'use strict';

angular.module('kifi')


.directive('kfConnectionCard', ['$window', '$http', 'routeService', 'inviteService', 'modalService',
  function ($window, $http, routeService, inviteService, modalService) {
  return {
    scope: {
      'friend': '&',
      'showAddNetworksModal': '&'
    },
    replace: true,
    restrict: 'A',
    templateUrl: 'invite/connectionCard.tpl.html',
    link: function (scope) {
      var friend = scope.friend();
      var network = friend.network;
      var inNetworkId = friend.identifier;
      var invited = (friend.lastInvitedAt != null);

      if (friend.pictureUrl) {
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
      scope.twitter  = network === 'twitter';

      scope.reconnectLinkedIn = function () {
        modalService.open({
          template: 'social/addNetworksModal.tpl.html'
        });
      };

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
            modalService.open({
              template: 'invite/linkedinTokenExpiredModal.tpl.html',
              scope: scope
            });
          } else if (err === 'hit_rate_limit_reached') {
            modalService.open({
              template: 'invite/linkedinHitRateLimitModal.tpl.html'
            });
          } else {
            modalService.open({
              template: 'invite/genericInviteErrorModal.tpl.html'
            });
          }
        });
      };

      scope.closeAction = function () {
        scope.hidden = true;
        var data = {
          'network': friend.network,
          'identifier': friend.identifier
        };
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
        scope.byline = network === 'email' ? inNetworkId :
          (network === 'linkedin' ? 'LinkedIn' : network.charAt(0).toUpperCase() + network.slice(1));
        scope.actionText = 'Invite';
      }
    }
  };
}]);
