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
        var defaultCloseAction = null;
        this.setDefaultCloseAction = function (action) {
          defaultCloseAction = action || null;
        };

        $scope.close = this.close = function (closeAction) {
          if (angular.isFunction(closeAction)) {
            closeAction();
          } else if (defaultCloseAction) {
            defaultCloseAction();
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
        
        kfModalCtrl.setDefaultCloseAction(scope.cancel);
        scope.cancelAndClose = function () {
          kfModalCtrl.close(scope.cancel);
        };
        scope.actionAndClose = function () {
          kfModalCtrl.close(scope.action);
        };

        var wrap = element.find('.dialog-body-wrap');

        var resizeWindow = _.debounce(function () {
          var winHeight = $window.innerHeight;
          wrap.css({'max-height': winHeight - 160 + 'px', 'overflow-y': 'auto', 'overflow-x': 'hidden'});
        }, 100);

        // scope.$watch(function () {
        //   return kfModalCtrl.show;
        // }, function (show) {
        //   if (show) {
        //     resizeWindow();
        //     $window.addEventListener('resize', resizeWindow);
        //   } else {
        //     $window.removeEventListener('resize', resizeWindow);
        //   }
        // });

        // scope.$on('$destroy', function () {
        //   $window.removeEventListener('resize', resizeWindow);
        // });

        scope.close = kfModalCtrl.close;
      }
    };
  }
]);
