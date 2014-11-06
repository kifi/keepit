'use strict';

angular.module('kifi')

.directive('kfModal', [
  '$document', 'modalService', '$window',
  function ($document, modalService, $window) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        forceClose: '='
      },
      templateUrl: 'common/modal/modal.tpl.html',
      transclude: true,
      controller: ['$scope', function ($scope) {
        function escapeModal (event) {
          if (event.which === 27) {  // Escape key
            $scope.close();
          }
        }

        function onOpen() {
          $document.on('keydown', escapeModal);
          $document.find('body').addClass('modal-open');
        }

        function onCloseOrDestroy() {
          $document.off('keydown', escapeModal);
          $document.find('body').removeClass('modal-open');
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

        scope.$watch(function () {
          return scope.forceClose;
        }, function (newVal, oldVal) {
          if (!oldVal && newVal) {
            scope.close();
          }
        });

        var wrap = element.find('.dialog-body-wrap');
        var resizeWindow = _.debounce(function () {
          var winHeight = $window.innerHeight;
          var modalHeight = wrap.height();
          debugger;
          wrap.css({'max-height': winHeight - 160 + 'px', 'overflow-y': 'auto', 'overflow-x': 'auto'});
        }, 100);
        $window.addEventListener('resize', resizeWindow);

        scope.$on('$destroy', function () {
          $window.removeEventListener('resize', resizeWindow);
        });
      }
    };
  }
])

.directive('kfBasicModalContent', ['$window',
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
      scope.actionText = attrs.actionText;
      scope.withCancel = (attrs.withCancel !== void 0) || false;
      scope.withWarning = (attrs.withWarning !== void 0) || false;
      scope.cancelText = attrs.cancelText;
      scope.centered = attrs.centered;

      // Note: if there is no 'single-action' attribute,
      // scope.singleAction will be set to true.
      scope.singleAction = attrs.singleAction || true;

      var wrap = element.find('.dialog-body-wrap');
      var resizeWindow = _.debounce(function () {
        var winHeight = $window.innerHeight;
        wrap.css({'max-height': winHeight - 160 + 'px', 'overflow-y': 'auto', 'overflow-x': 'hidden'});
      }, 100);
      $window.addEventListener('resize', resizeWindow);

      scope.closeAndCancel = function () {
        $window.removeEventListener('resize', resizeWindow);
        kfModalCtrl.close(scope.cancel);
      };
      scope.closeAndAction = function () {
        $window.removeEventListener('resize', resizeWindow);
        kfModalCtrl.close(scope.action);
      };

      scope.close = function () {
        $window.removeEventListener('resize', resizeWindow);
        kfModalCtrl.close();
      };

      scope.$on('$destroy', function () {
        $window.removeEventListener('resize', resizeWindow);
      });
    }
  };
}]);
