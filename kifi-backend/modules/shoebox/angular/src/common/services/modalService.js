'use strict';

angular.module('kifi')

.factory('modalService', ['$compile', '$rootScope', '$templateCache', '$timeout',
  function ($compile, $rootScope, $templateCache, $timeout) {
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

      // We need to save a reference to the scope, because .append() appears to set the scope of the element
      // as .kf-cols's scope. Clearly we're missing something trivial (using a scope in $compile and then
      // assigning it to the DOM node's scope), so if someone can fix it, go for it :).
      modals.push([$modal, scope]);

      return scope;
    }

    function close () {
      var ref = modals.pop();
      if (ref && ref.length > 0 && ref[0].length) {
        var $modal = ref[0];
        var scope = ref[1];
        $timeout(function () {
          scope.$destroy();
        });
        $modal.remove();
      }
    }

    return {
      open: open,
      close: close
    };
  }
]);
