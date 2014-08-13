'use strict';

angular.module('kifi.modalService', [])

.factory('modalService', ['$compile', '$rootScope', '$timeout',
  function ($compile, $rootScope, $timeout) {
    var modalConfigs = {
      seeMutualFriends: {
        template: '<div kf-see-mutual-friends></div>',
        noUserHide: true,
        className: 'kf-see-mutual-friends-modal',
        width: '600px',
        noSingleAction: true 
      }
    };

    function open (opts) {
      opts = opts || {};

      if (!opts.name) {
        return;
      }
      var config = modalConfigs[opts.name] || {};

      var template = config.template;
      if (!template) {
        return;
      }

      var scope = $rootScope.$new();
      scope.show = true;
      var $modal = angular.element('<div id="kf-modal" kf-modal show="show"></div>');
      var $modalContent = angular.element('<div kf-basic-modal-content>' + template + '</div>');

      if (config.className) {
        $modal.addClass(config.className);
      }

      if (config.noUserHide) {
        // Setting attribute to an empty string results in the 
        // attribute being set without a value.
        // E.g., <div no-user-hide></div>
        $modal.attr('no-user-hide', '');
      }

      if (config.width) {
        $modal.attr('kf-width', config.width);
      }

      if (config.height) {
        $modal.attr('kf-height', config.height);
      }

      if (config.opacity) {
        $modal.attr('kf-opacity', config.opacity);
      }

      if (config.backdropColor) {
        $modal.attr('kf-backdrop-color', config.backdropColor);
      }

      if (config.noSingleAction) {
        $modalContent.attr('single-action', 'false');
      }

      if (opts.modalData) {
        scope.modalData = opts.modalData;
      }

      $modal.append($modalContent);
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
