'use strict';

angular.module('kifi')

.directive('kfOrgProfileHeader', ['$state', function($state) {
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

      scope.save = function () {
        
      }
    }
  };
}]);
