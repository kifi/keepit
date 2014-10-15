'use strict';

angular.module('kifi')

.factory('modalService', ['$compile', '$rootScope', '$templateCache',
  function ($compile, $rootScope, $templateCache) {
    var modals = [];

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
      angular.element(document.body).find('.kf-cols').append($modal);

      modals.push($modal);
    }

    function close () {
      var $modal = modals.pop();
      $modal.scope().$destroy();
      $modal.remove();
    }

    return {
      open: open,
      close: close
    };
  }
]);
