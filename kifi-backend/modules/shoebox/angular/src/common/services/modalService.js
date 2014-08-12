'use strict';

angular.module('kifi.modalService', [])

.factory('modalService', ['$compile', '$rootScope', '$timeout',
  function ($compile, $rootScope, $timeout) {
    function open (opts) {
      opts = opts || {};

      if (!opts.template) {
        return;
      }
      var template = opts.template;

      var scope = $rootScope.$new();
      scope.show = true;
      var $modal = angular.element('<div id="kf-modal" kf-modal show="show" single-action="false" kf-width="600px"></div>');
      $modal.html('<div kf-basic-modal-content>' + template + '</div>');

      if (opts.className) {
        $modal.addClass(opts.className);
      }

      if (opts.modalData) {
        scope.modalData = opts.modalData;
      }

      $timeout(function () {
        $compile($modal)(scope);
        angular.element(document.body).append($modal);
      });
    }

    function close () {
      var $modal = angular.element(document.getElementById('kf-modal'));
      $modal.scope().$destroy();
      $modal.remove();
    }

    return {
      open: open,
      close: close
    };
  }
]);
