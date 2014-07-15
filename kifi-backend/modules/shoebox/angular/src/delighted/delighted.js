'use strict';

angular.module('kifi.delighted', [])

.directive('kfDelightedSurvey', [
  '$timeout', 'profileService',
  function ($timeout, profileService) {
    return {
      restrict: 'A',
      scope: {},
      templateUrl: 'delighted/delightedSurvey.tpl.html',
      link: function (scope, element) {
        scope.delighted = {};
        scope.showCommentArea = false;
        scope.showSurvey = true;

        scope.$watch('delighted.score', function () {
          if (scope.delighted.score) {
            scope.showCommentArea = true;
            $timeout(function () {
              element.find('.kf-delighted-comment-area-input').focus();
            });
          }
        });

        scope.goBack = function () {
          scope.delighted.score = null;
          scope.showCommentArea = false;
        };

        scope.submit = function () {
          console.log(scope.delighted.score);
          profileService.postDelightedAnswer(+scope.delighted.score, scope.delighted.comment || null);
          scope.showSurvey = false;
        };

        scope.cancelSurvey = function () {
          profileService.cancelDelightedSurvey();
          scope.showSurvey = false;
        };
      }
    };
  }
]);
