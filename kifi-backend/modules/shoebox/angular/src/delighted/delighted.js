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
        scope.currentScore = null;
        scope.showCommentArea = false;
        scope.showSurvey = true;

        var textarea = element.find('.kf-delighted-comment-area-input');

        scope.clickedScore = function (score) {
          scope.currentScore = score;
          scope.showCommentArea = true;
          $timeout(function () {
            textarea.focus();
          });
        };

        scope.goBack = function () {
          scope.showCommentArea = false;
        };

        scope.submit = function () {
          profileService.postDelightedAnswer(scope.currentScore, textarea.val() || null);
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
