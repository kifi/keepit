'use strict';

angular.module('kifi')

.directive('kfModal', [
  '$document', 'modalService',
  function ($document, modalService) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        show: '='
      },
      templateUrl: 'common/modal/modal.tpl.html',
      transclude: true,
      controller: ['$scope', function ($scope) {
        $scope.close = this.close = function (closeAction) {
          if (angular.isFunction(closeAction)) {
            closeAction();
          }

          $document.off('keydown', escapeModal);
          
          modalService.close();
        };

        function escapeModal (event) {
          if (event.which === 27) {  // Escape key
            $scope.close();
          }
        }
        $document.on('keydown', escapeModal);
      }],
      link: function (scope, element, attrs) {
        scope.dialogStyle = {};
        scope.backdropStyle = {};
        scope.noUserHide = (attrs.noUserHide !== void 0) || false;

        if (attrs.kfWidth) {
          scope.dialogStyle.width = attrs.kfWidth;
        }
        if (attrs.kfHeight) {
          scope.dialogStyle.height = attrs.kfHeight;
        }

        scope.backdropStyle.opacity = attrs.kfOpacity || 0.3;
        scope.backdropStyle.backgroundColor = attrs.kfBackdropColor || 'rgba(0, 40, 90, 1)';
      }
    };
  }
])

.directive('kfBasicModalContent', [function () {
  return {
    restrict: 'A',
    replace: true,
    scope: {
      action: '&',
      cancel: '&',
      title: '@'
    },
    templateUrl: 'common/modal/basicModalContent.tpl.html',
    transclude: true,
    require: '^kfModal',
    link: function (scope, element, attrs, kfModalCtrl) {
      scope.title = attrs.title || '';
      scope.actionText = attrs.actionText;
      scope.withCancel = (attrs.withCancel !== void 0) || false;
      scope.withWarning = (attrs.withWarning !== void 0) || false;
      scope.cancelText = attrs.cancelText;
      scope.centered = attrs.centered;

      // Note: if there is no 'single-action' attribute,
      // scope.singleAction will be set to true.
      scope.singleAction = attrs.singleAction || true;

      scope.cancelAndClose = function () {
        kfModalCtrl.close(scope.cancel);
      };
      scope.actionAndClose = function () {
        kfModalCtrl.close(scope.action);
      };

      scope.close = kfModalCtrl.close;
    }
  };
}]);
