'use strict';

angular.module('kifi.layout.rightCol', ['kifi.modal'])

.controller('RightColCtrl', [
  '$scope', '$window', 'kfModal',
  function ($scope, $window, kfModal) {
    $scope.gettingStarted = function () {
      var modalInstance = kfModal.open({
        //template: '<div class="modal-header"><h3>I\'m a modal!</h3></div><div class="modal-body">What what</div><div class="modal-footer"><button class="btn btn-primary" ng-click="ok()">OK</button><button class="btn btn-warning" ng-click="cancel()">Cancel</button></div>',
        template: 'Hey',
        resolve: {
          items: function () {
            return $scope.items;
          }
        }
      });

      modalInstance.result.then(function (selectedItem) {
        $scope.selected = selectedItem;
      }, function () {
        $window.console.log("word")
      });
    };
    $window.console.log('RightColCtrl');
  }
]);
