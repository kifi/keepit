'use strict';

angular.module('kifi')

.factory('modalService', ['$compile', '$rootScope', '$templateCache',
  function ($compile, $rootScope, $templateCache) {
    function open (opts) {
      opts = opts || {};

      var template = opts.template;
      if (!template) {
        return;
      }
      var $modal = angular.element($templateCache.get(template));

      var scope = opts.scope || $rootScope.$new();
      if (opts.modalData) {
        scope.modalData = opts.modalData;
      }

      $compile($modal)(scope);
      angular.element(document.body).append($modal);
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
