'use strict';

angular.module('kifi.keepWhoPics', [])

.directive('kfKeepWhoPics', [

  function () {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'keep/keepWhoPics.tpl.html',
      scope: {
        me: '=',
        keepers: '='
      },
      link: function (scope) {
        scope.getPicUrl = function (user) {
          if (user) {
            return '//djty7jcqog9qu.cloudfront.net/users/' + user.id + '/pics/100/' + user.pictureName;
          }
          return '';
        };
      }
    };
  }
]);
