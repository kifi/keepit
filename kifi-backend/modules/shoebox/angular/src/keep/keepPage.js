'use strict';

angular.module('kifi')

.controller('KeepPageCtrl', [ '$scope', 'keep',
  function ($scope, keep) {
    $scope.keep = keep;
  }
]);
