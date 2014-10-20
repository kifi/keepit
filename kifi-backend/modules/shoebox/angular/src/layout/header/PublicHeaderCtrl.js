'use strict';

angular.module('kifi')

.controller('PublicHeaderCtrl', ['$scope', 'env',
  function ($scope, env) {
    $scope.navBase = env.navBase;
  }
]);
