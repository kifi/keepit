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
        };

        $scope.toggleEditing = function () {
          $scope.editing = !$scope.editing;
        };

        $scope.enableEdit = function () {
          $scope.editing = true;
        };

        $scope.disableEdit = function () {
          $scope.editing = false;
        };

        $scope.triggerKeyUp = function ($event) {
          $scope.text = $event.target.value;
        };

        function click(e) {
          // console.log('target is child of $element?', $element.has(e.target).length);
          // console.log(e.target);
          if ($element.has(e.target).length) {
            // console.log('editing ', true);
            $scope.enableEdit();
          } else {
            // console.log('editing ', false);
            $scope.disableEdit();
          }
        }

        $scope.click = click;

        $document.on('click', click);

        $scope.$on('destroy', function () {
          $document.off('click', click);
        });

      }
    };
  }
]);
