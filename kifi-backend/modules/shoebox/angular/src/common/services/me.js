'use strict';

angular.module('kifi')

.service('me', function () {
  return {
    replace: true,
    restrict: 'A',
    templateUrl: 'profileCard/profileCard.tpl.html',
    link: function (scope) {
      scope.firstName = 'Joon Ho';
      scope.lastName = 'Cho';
      scope.description = 'Porting to Angular.js';
    }
  };
});
