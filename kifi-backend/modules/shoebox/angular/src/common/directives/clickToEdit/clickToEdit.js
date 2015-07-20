'use strict';

angular.module('kifi')

.directive('kfClickToEdit', [
  '$document',
  function ($document) {
    return {
      templateUrl: 'common/directives/clickToEdit/clickToEdit.tpl.html',
      scope: {
        text: '='
      },
      replace: true,
      link: function ($scope, $element) {
        $scope.editing = false;

        $scope.isEditing = function () {
          return $scope.editing;
        }

        $scope.toggleEditing = function () {
          $scope.editing = !$scope.editing;
        };

        $scope.triggerKeyUp = function ($event) {
          $scope.text = $event.target.value;
        };

        function click(e) {
          if ($element.has(e.target).length) {
            $scope.editing = true;
          } else {
            $scope.editing = false;
          }
        }

        //$scope.click = click;

        $document.on('click', click);

        $scope.$on('destroy', function () {
          $document.off('click', click)
        });

      }
    };
  }
]);
