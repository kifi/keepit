'use strict';

angular.module('kifi')

.directive('kfRegister', [
  function() {
    return {
      restrict: 'A',
      require: '^kfModal',
      $scope: {},
      templateUrl: 'signup/register.tpl.html',
      link: function ($scope, element) {
        element.find('.register-email-box').focus();
      }
  };
}]);
