'use strict';

angular.module('kifi')

.directive('kfTagLibrary', ['$location',
  function ($location) {
    return {
      restrict: 'A',
      require: '^kfModal',
      templateUrl: 'tagManage/tagToLibMsg.tpl.html',
      link: function (scope, element, attrs, kfModalCtrl) {

        scope.hide = function () {
          kfModalCtrl.close();
        };

        scope.navigateToLibrary = function () {
          $location.path(scope.modalData.library.url);
          kfModalCtrl.close();
        };
      }
    };
  }
]);
