'use strict';

angular.module('kifi')

.controller('KeepViewCtrl', [
  '$scope', '$routeParams', '$rootScope', 'keepActionService', 'keepDecoratorService',
  function ($scope, $routeParams, $rootScope, keepActionService, keepDecoratorService) {
    //
    // Internal data.
    //
    var keepId = $routeParams.keepId || '';


    //
    // Scope data.
    //
    $scope.keeps = [];
    $scope.loading = true;


    //
    // Scope methods.
    //
    $scope.getSubtitle = function () {
      if ($scope.loading) {
        return 'Loading...';
      } else {
        return 'Showing 1 keep';
      }
    };

    
    //
    // On KeepViewCtrl initialization.
    //
    keepActionService.getSingleKeep(keepId).then(function (rawKeep) {
      var keep = new keepDecoratorService.Keep(rawKeep);
      keep.buildKeep(keep);
      keep.makeKept();

      $scope.keeps = [keep];
      $scope.loading = false;
    });

    $rootScope.$emit('libraryUrl', {});
  }
]);
