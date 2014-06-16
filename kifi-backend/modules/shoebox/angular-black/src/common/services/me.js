'use strict';

angular.module('kifi.me', [])

.service('me', function () {
  return {
    replace: true,
    restrict: 'A',
    templateUrl: 'profileCard/profileCard.tpl.html',
    link: function (scope /*, element, attrs*/ ) {
      scope.firstName = 'Joon Ho';
      scope.lastName = 'Cho';
      scope.description = 'Porting to Angular.js';
    }
  };
});
