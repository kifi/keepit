'use strict';

angular.module('kifi')

.controller('OrgProfileSlackLibraryCtrl', ['$scope', '$stateParams', 'libraryService',
  function ($scope, $stateParams, libraryService) {
    $scope.library = null;
    $scope.owner = null;
    libraryService.getLibraryInfoById($stateParams.libraryId).then(function(data) {
      $scope.library = data.library;
      $scope.owner = data.library.owner;
    });
  }
]);
