'use strict';

angular.module('kifi')

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
          if ($scope.noUserHide && $scope.show) {
            // hide is disabled for user and was not triggered by change of state
            return;
          }
          if (typeof hideAction === 'function') {
            hideAction();
          } else if (defaultHideAction) {
            defaultHideAction();
          }
          $scope.show = false;
        };

        this.show = $scope.show || false;

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
          this.show = $scope.show || false;
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

.directive('kfBasicModalContent', [
  '$window',
  function ($window) {
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

        var wrap = element.find('.dialog-body-wrap');

        var resizeWindow = _.debounce(function () {
          var winHeight = $window.innerHeight;
          wrap.css({'max-height': winHeight - 160 + 'px', 'overflow-y': 'auto', 'overflow-x': 'hidden'});
        }, 100);

        scope.$watch(function () {
          return kfModalCtrl.show;
        }, function (show) {
          if (show) {
            resizeWindow();
            $window.addEventListener('resize', resizeWindow);
          } else {
            $window.removeEventListener('resize', resizeWindow);
          }
        });

        scope.$on('$destroy', function () {
          $window.removeEventListener('resize', resizeWindow);
        });
      }
    };
  }
]);
