/**
 * This service is here because Firefox doesn't provide the
 * mouse position information in drag events
 */

'use strict';

angular.module('kifi')

.factory('dragService', [
  '$document',
  function ($document) {
    var pageX, pageY;

    $document.on('dragover', function (e) {
      e = e.originalEvent;
      pageX = e.clientX || e.pageX;
      pageY = e.clientY || e.pageY;
    });

    return {
      getDragPosition: function () {
        return {
          pageX: pageX,
          pageY: pageY
        };
      }
    };
  }
]);
