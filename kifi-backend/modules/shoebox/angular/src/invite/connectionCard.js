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
      scope.email    = network === 'email';
      scope.twitter  = network === 'twitter';

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
        }, function () {
            modalService.open({
              template: 'invite/genericInviteErrorModal.tpl.html'
            });
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
          (network.charAt(0).toUpperCase() + network.slice(1));
        scope.actionText = 'Invite';
      }
    }
  };
}]);
