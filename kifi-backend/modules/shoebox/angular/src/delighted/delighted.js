'use strict';

angular.module('kifi')

.directive('kfDelightedSurvey', [
  '$timeout', '$analytics', 'profileService',
  function ($timeout, $analytics, profileService) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        hide: '='
      },
      templateUrl: 'delighted/delightedSurvey.tpl.html',
      link: function (scope, element) {
        scope.delighted = {};

        var answerId = null;

        function analyticsStageName() {
          return {score: 'npsScore', comment: 'npsComment', end: 'npsThanks'}[scope.surveyStage];
        }

        scope.surveyStage = 'score';
        $analytics.eventTrack('user_viewed_notification', {
          'source': 'site',
          'type': analyticsStageName()
        });

        scope.$watch('delighted.score', function () {
          if (scope.delighted.score) {
            submitAnswer();
            scope.surveyStage = 'comment';
            $analytics.eventTrack('user_viewed_notification', {
              'source': 'site',
              'type': analyticsStageName()
            });
            $timeout(function () {
              element.find('.kf-delighted-comment-area-input').focus();
            });
          }
        });

        scope.goBack = function () {
          scope.delighted.score = null;
          scope.surveyStage = 'score';
        };

        scope.submitComment = function () {
          submitAnswer();
          scope.surveyStage = 'end';
          $analytics.eventTrack('user_viewed_notification', {
            'source': 'site',
            'type': analyticsStageName()
          });
          $timeout(hideSurvey, 3000);
        };

        scope.cancelSurvey = function () {
          $analytics.eventTrack('user_clicked_notification', {
            'source': 'site',
            'action': 'closed',
            'type': analyticsStageName()
          });
          profileService.cancelDelightedSurvey();
          hideSurvey();
        };

        function hideSurvey() {
          scope.hide();
        }

        function submitAnswer() {
          profileService.postDelightedAnswer(
            +scope.delighted.score || null,
            scope.delighted.comment || null,
            answerId
          ).then(function (id) {
            answerId = id;
          });
        }
      }
    };
  }
]);
