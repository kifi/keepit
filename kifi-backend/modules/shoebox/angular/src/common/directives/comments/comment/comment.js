'use strict';

angular.module('kifi')

  .directive('kfKeepComment', [
    function () {
      return {
        restrict: 'A',
        scope: {
          comment: '='
        },
        templateUrl: 'common/directives/comments/comment/comment.tpl.html',
        link: function () {

        }
      };
    }
  ]);
