'use strict';

angular.module('kifi')

.directive('kfOrgProfileHeader', ['$state', '$http', '$analytics', '$location', 'net', function($state, $http, $analytics, $location, net) {
  return {
    restrict: 'A',
    scope: {
      profile: '='
    },
    templateUrl: 'orgProfile/orgProfileHeader.tpl.html',
    link: function (scope, element) {
      scope.editing = false;
      var lastSavedInfo = {};

      scope.toggleEditing = function () {
        scope.editing = !scope.editing;
        lastSavedInfo = angular.extend(lastSavedInfo, scope.profile);
      }

      scope.undo = function () {
        scope.profile = angular.extend(scope.profile, lastSavedInfo);
        scope.toggleEditing()
      }

      var updateMe = function(data) {
        angular.extend(scope.profile, data);
      }

      scope.save = function () {
        var data = {
          name: scope.profile.name,
          link: scope.profile.link,
          description: scope.profile.description
        };

        return net.updateOrgProfile(scope.profile.id, data).then(function (res) {
          $analytics.eventTrack('user_clicked_page', {
            'action': 'updateOrgProfile',
            'path': $location.path()
          });
          return updateMe(res.data);
        });
      }
    }
  };
}]);
