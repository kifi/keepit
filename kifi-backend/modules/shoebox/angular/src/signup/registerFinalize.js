'use strict';

angular.module('kifi')

.directive('kfRegisterFinalize', [
  function() {
    return {
      restrict: 'A',
      require: '^kfModal',
      $scope: {},
      templateUrl: 'signup/registerFinalize.tpl.html',
      link: function($scope, element) {
        element.find('.register-first-name').focus();
      }
    };
  }
]);
