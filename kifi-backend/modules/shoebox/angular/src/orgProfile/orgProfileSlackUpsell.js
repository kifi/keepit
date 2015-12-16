'use strict';

angular.module('kifi')

.directive('kfOrgProfileSlackUpsell', [
  '$window',
  function ($window) {
    return {
      restrict: 'A',
      require: '^kfModal',
      scope: {
        getLibrary: '&library'
      },
      templateUrl: 'orgProfile/orgProfileSlackUpsell.tpl.html',
      link: function ($scope, element, attrs, kfModalCtrl) {
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

        $scope.close = function() {
          kfModalCtrl.close();
        };
      }
    };
  }
]);
