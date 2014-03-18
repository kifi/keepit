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
      controller: ['$scope', function ($scope) {
        var defaultHideAction = null;

        this.setDefaultHideAction = function (action) {
          defaultHideAction = action;
        };

        this.hideModal = function (hideAction) {
          if (typeof hideAction === 'function') {
            hideAction();
          } else if (defaultHideAction) {
            defaultHideAction();
          }
          $scope.show = false;
        };

        $scope.hideModal = this.hideModal;

        function exitModal(evt) {
          if (evt.which === 27) {
            $scope.hideModal(evt);
            $scope.$apply();
          }
        }

        $scope.$watch(function () {
          return $scope.show;
        }, function () {
          if ($scope.show) {
            $document.on('keydown', exitModal);
          } else {
            $document.off('keydown', exitModal);
          }
        });

        $scope.$on('$destroy', function () {
          $document.off('keydown', exitModal);
        });
      }],
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
        title: '@'
      },
      templateUrl: 'common/modal/basicModalContent.tpl.html',
      transclude: true,
      require: '^kfModal',
      link: function (scope, element, attrs, kfModalCtrl) {
        scope.title = attrs.title || '';
        scope.singleAction = attrs.singleAction || true;
        scope.actionText = attrs.actionText;
        scope.withCancel = (attrs.withCancel !== void 0) || false;
        scope.withWarning = (attrs.withWarning !== void 0) || false;
        scope.cancelText = attrs.cancelText;
        scope.centered = attrs.centered;
        kfModalCtrl.setDefaultHideAction(scope.cancel);

        scope.hideAndCancel = kfModalCtrl.hideModal;
        scope.hideAndAction = function () {
          kfModalCtrl.hideModal(scope.action);
        };
      }
    };
  }
]);
