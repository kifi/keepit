'use strict';

angular.module('kifi.modal', [])

.directive('kfModal', [
  '$document',
  function ($document) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        show: '='
      },
      templateUrl: 'common/modal/modal.tpl.html',
      transclude: true,
      link: function (scope, element, attrs) {
        scope.dialogStyle = {};
        scope.backdropStyle = {};

        if (attrs.kfWidth) {
          scope.dialogStyle.width = attrs.kfWidth;
        }
        if (attrs.kfHeight) {
          scope.dialogStyle.height = attrs.kfHeight;
        }

        scope.backdropStyle.opacity = attrs.kfOpacity || 0.3;
        scope.backdropStyle.backgroundColor = attrs.kfBackdropColor || 'rgba(0, 40, 90, 1)';

        scope.hideModal = function () {
          scope.show = false;
        };

        function exitModal(evt) {
          if (evt.which === 27) {
            scope.hideModal(evt);
            scope.$apply();
          }
        }

        scope.$watch(function () {
          return scope.show;
        }, function () {
          if (scope.show) {
            $document.on('keydown', exitModal);
          } else {
            $document.off('keydown', exitModal);
          }
        });

      }
    };
  }
])

.directive('kfBasicModal', [
  '$document',
  function ($document) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        show: '='
      },
      templateUrl: 'common/modal/basicModal.tpl.html',
      transclude: true,
      link: function (scope, element, attrs) {
        scope.dialogStyle = {};
        scope.backdropStyle = {};

        if (attrs.kfWidth) {
          scope.dialogStyle.width = attrs.kfWidth;
        }
        if (attrs.kfHeight) {
          scope.dialogStyle.height = attrs.kfHeight;
        }

        scope.title = attrs.title || '';
        scope.singleAction = attrs.singleAction || true;
        scope.actionText = attrs.actionText;

        scope.backdropStyle.opacity = attrs.kfOpacity || 0.3;
        scope.backdropStyle.backgroundColor = attrs.kfBackdropColor || 'rgba(0, 40, 90, 1)';

        scope.hideModal = function () {
          scope.show = false;
        };

        function exitModal(evt) {
          if (evt.which === 27) {
            scope.hideModal(evt);
            scope.$apply();
          }
        }

        scope.$watch(function () {
          return scope.show;
        }, function () {
          if (scope.show) {
            $document.on('keydown', exitModal);
          } else {
            $document.off('keydown', exitModal);
          }
        });

      }
    };
  }
]);
