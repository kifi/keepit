'use strict';

angular.module('kifi')

.factory('modalService', ['$compile', '$rootScope', '$templateCache',
  function ($compile, $rootScope, $templateCache) {
    var modalScope;
    function open (opts) {
      opts = opts || {};

      var template = opts.template;
      if (!template) {
        return;
      }
      var $modal = angular.element($templateCache.get(template));

      var scope = (opts.scope && opts.scope.$new()) || $rootScope.$new();
      if (opts.modalData) {
        scope.modalData = opts.modalData;
      }

      $compile($modal)(scope);

      modalScope = scope;
      angular.element(document.body).append($modal);
      return scope;
    }

    function close () {
      var $modal = angular.element(document.getElementById('kf-modal'));
      modalScope.$destroy();
      modalScope = null;
      $modal.remove();
    }

    return {
      open: open,
      close: close
    };
  }
]);
