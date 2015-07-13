angular.module('kifi')

.directive('kfOrganizationMembers', [
  '$timeout', 'net',
  function ($timeout, net) {
    return {
      restrict: 'A',
      templateUrl: 'members/member.tpl.html',
      link: function (scope, element, attrs) {
        
      }
    };
  }
]);
