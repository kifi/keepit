'use strict';

angular.module('kifi')

.controller('KeepPageCtrl', [ '$rootScope', '$location', '$scope', '$state', '$stateParams', 'keepActionService', 'modalService',
  function ($rootScope, $location, $scope, $state, $stateParams, keepActionService, modalService) {
    keepActionService.getFullKeepInfo($stateParams.pubId, $stateParams.authToken).then(function (result) {
      $scope.loaded = true;
      $scope.keep = result;
    })['catch'](function(reason){
      $scope.loaded = true;
      $rootScope.$emit('errorImmediately', reason);
    });
    $scope.unkeepFromLibrary = function (event, keep) {
      if (keep.libraryId && keep.id) {
        keepActionService.unkeepFromLibrary(keep.libraryId, keep.id).then(function () {
          var libPath = keep.library && keep.library.path;
          if (libPath) {
            $location.path(keep.library.path);
          } else {
            $state.go('home.feed');
          }
        })['catch'](function (err) {
          modalService.openGenericErrorModal(err);
        });
      }
    };
  }
]);
