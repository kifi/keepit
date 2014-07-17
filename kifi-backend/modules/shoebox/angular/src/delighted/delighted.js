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
        scope.surveyStage = 'score';
        scope.showSurvey = true;

        scope.$watch('delighted.score', function () {
          if (scope.delighted.score) {
            scope.surveyStage = 'comment';
            $timeout(function () {
              element.find('.kf-delighted-comment-area-input').focus();
            });
          }
        });

        scope.goBack = function () {
          scope.delighted.score = null;
          scope.surveyStage = 'score';
        };

        scope.submit = function () {
          profileService.postDelightedAnswer(+scope.delighted.score, scope.delighted.comment || null);
          scope.surveyStage = 'end';
          $timeout(function () {
            hideSurvey();
          }, 3000);
        };

        scope.cancelSurvey = function () {
          profileService.cancelDelightedSurvey();
          hideSurvey();
        };

        function hideSurvey() {
          element.find('.kf-delighted-wrap').addClass('hide');
        }
      }
    };
  }
]);
