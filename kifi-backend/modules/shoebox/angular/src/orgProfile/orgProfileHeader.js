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
      scope = scope;
      element = element;
    }
  };
}]);
