'use strict';

angular.module('kifi')

.directive('kfOrgProfileSlackUpsell', [
  '$window', '$rootScope', 'messageTicker',
  function ($window, $rootScope, messageTicker) {
    return {
      restrict: 'A',
      require: '^kfModal',
      scope: {
        getLibrary: '&library',
        getOrg: '&org'
      },
      templateUrl: 'orgProfile/orgProfileSlackUpsell.tpl.html',
      link: function ($scope, element, attrs, kfModalCtrl) {
        $analytics.eventTrack('user_viewed_pane', { type: 'orgProfileSlackUpsell'});
        $scope.userLoggedIn = $rootScope.userLoggedIn;

        function getSlackLink() {
          var library = $scope.getLibrary();
          return library.slack && (library.slack.link || '').replace('search%3Aread%2Creactions%3Awrite', '');
        }

        $scope.integrateWithSlack = function() {
          var library = $scope.getLibrary();
          if ((library.permissions || []).indexOf('create_slack_integration') !== -1) {
            $window.location = getSlackLink();
          }
        };

        $scope.onClickedSynOnlyGeneral = function() {
          $analytics.eventTrack('user_clicked_pane', { type: 'orgProfileSlackUpsell', action: 'syncGeneral' });
          var library = $scope.getLibrary();
          if ((library.permissions || []).indexOf('create_slack_integration') !== -1) {

            $window.location = getSlackLink();
          }
          kfModalCtrl.close();
        };

        $scope.onClickedSyncAllSlackChannels = function() {
          var org = $scope.getOrg();
          $analytics.eventTrack('user_clicked_pane', { type: 'orgProfileSlackUpsell', action: 'syncAllChannels' });
          if (org && org.slack && org.slack.link) {
            $window.location = org.slack.link;
          } else {
            messageTicker({
              text: 'Unable to retrieve Team information, please refresh and try again.',
              type: 'red'
            });
          }
          kfModalCtrl.close();
        };

        $scope.close = function() {
          kfModalCtrl.close();
        };
      }
    };
  }
]);
