'use strict';

angular.module('kifi')

.directive('kfNewSlackUserTeamUpsell', [
  '$window', '$analytics',
  function ($window, $analytics) {
    return {
      restrict: 'A',
      require: '^kfModal',
      scope: {},
      templateUrl: 'slack/newSlackUserTeamUpsell.tpl.html',
      link: function ($scope, element, attrs, kfModalCtrl) {
        $analytics.eventTrack('user_viewed_pane', { type: 'newSlackUserTeamUpsell'});

        $scope.onClickCreateTeam = function () {
          $window.location = 'https://www.kifi.com/integrations/slack/start';
        };

        $scope.close = function() {
          kfModalCtrl.close();
        };
      }
    };
  }
]);
