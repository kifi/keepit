'use strict';

angular.module('kifi.addKeeps', ['kifi.profileService'])

.directive('kfAddKeepsModal', [
  function () {
    return {
      replace: true,
      restrict: 'A',
      templateUrl: 'keep/addKeepsModal.tpl.html',
      scope: {
        'data': '='
      },
      link: function (/*scope, element, attrs*/ ) {



      }
    };
  }
]);
