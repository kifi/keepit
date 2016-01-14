'use strict';

angular.module('kifi')

.directive('kfModal', [
  '$document', 'modalService',
  function ($document, modalService) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        disableScroll: '='
      },
      templateUrl: 'common/modal/modal.tpl.html',
      transclude: true,
      controller: ['$scope', function ($scope) {
        function onDocKeyDown(event) {
          if (event.which === 27) {  // Escape key
            $scope.close();
          }
        }

        function onOpen() {
          $document.on('keydown', onDocKeyDown);
        }

        function onCloseOrDestroy() {
          $document.off('keydown', onDocKeyDown);
        }

        $scope.close = this.close = function (closeAction) {
          onCloseOrDestroy();
          modalService.close();

          if (angular.isFunction(closeAction)) {
            closeAction();
          }
        };

        $scope.$on('$destroy', onCloseOrDestroy);

        onOpen();
      }],
      link: function (scope, element, attrs) {
        scope.dialogStyle = {
          'width': attrs.kfWidth ? '100%' : null,
          'max-width': attrs.kfWidth || '400px',
          'height': attrs.kfHeight
        };
        scope.backdropStyle = {};
        scope.noUserHide = (attrs.noUserHide !== void 0) || false;
        scope.backdropStyle.opacity = attrs.kfOpacity || 0.3;
        scope.backdropStyle.backgroundColor = attrs.kfBackdropColor || 'rgba(0, 40, 90, 1)';

        scope.$on('forceCloseModal', function () {
          scope.close();
        });

        if (!scope.disableScroll) {
          element.find('.dialog-body').css({'overflow-y': 'auto', 'overflow-x': 'hidden'});
        }
      }
    };
  }
])

.directive('kfBasicModalContent', [
  function () {
  return {
    restrict: 'A',
    replace: true,
    scope: {
      action: '&',
      cancel: '&',
      title: '@',
      actionText: '@'
    },
    templateUrl: 'common/modal/basicModalContent.tpl.html',
    transclude: true,
    require: '^kfModal',
    link: function (scope, element, attrs, kfModalCtrl) {
      element.removeAttr('title');  // TODO: use a different attribute name for modal title
  
      var modalDefaults = scope.$parent.modalDefaults || {};

      scope.title = attrs.title || modalDefaults.title || '';
      scope.actionText = attrs.actionText || modalDefaults.actionText;
      scope.cancelText = attrs.cancelText || modalDefaults.cancelText;
      scope.withCancel = (attrs.withCancel !== void 0) || modalDefaults.withCancel || false;

      scope.withOk = (typeof attrs.withOk !== 'undefined' ? (attrs.withOk !== 'false' || modalDefaults.withOk) : true); // default value of true

      scope.withWarning = (attrs.withWarning !== void 0) || modalDefaults.withWarning || false;
      scope.withCta = (attrs.withCta !== void 0) || modalDefaults.withCta || false;
      scope.equalButtons = (typeof attrs.equalButtons !== 'undefined' ? (attrs.equalButtons !== 'false'
                                                                                || modalDefaults.equalButtons) : true); // default value of true
      scope.withoutButtons = 'withoutButtons' in attrs || modalDefaults.withoutButtons;
      scope.centered = attrs.centered || modalDefaults.centered;

      scope.closeAndCancel = function () {
        kfModalCtrl.close(modalDefaults.cancel || scope.cancel);
      };
      scope.closeAndAction = function () {
        kfModalCtrl.close(modalDefaults.action || scope.action);
      };

      scope.close = function () {
        kfModalCtrl.close();
      };
    }
  };
}]);
