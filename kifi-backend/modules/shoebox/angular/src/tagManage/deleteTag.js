'use strict';

angular.module('kifi')

.directive('kfDeleteTag', ['$location', 'tagService',
  function ($location, tagService) {
    return {
      restrict: 'A',
      require: '^kfModal',
      templateUrl: 'tagManage/deleteTagMsg.tpl.html',
      link: function (scope, element, attrs, kfModalCtrl) {

        scope.deleteTag = function () {
          tagService.remove(scope.modalData.tag);
          _.remove(scope.modalData.tagsToShow, function(t) { return t === scope.modalData.tag; });
          kfModalCtrl.close();
          return scope.modalData.tagsToShow;
        };
      }
    };
  }
]);
