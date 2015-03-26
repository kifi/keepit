'use strict';

angular.module('nodraginput', [])

.directive('kfNoDragInput', [
  function () {
    return {
      restrict: 'A',
      link: function (scope, element) {

        function disableDragEffect(event) {
          if (event.dataTransfer) {
            event.dataTransfer.dropEffect = 'none';
          }
          event.stopPropagation();
          event.preventDefault();
        }

        element.attr('draggable', 'false');
        element.on('dragstart', function () { return false; });
        element.on('dragenter', disableDragEffect);
        element.on('dragover', disableDragEffect);
        element.on('drop', disableDragEffect);
      }
    };
  }
]);
