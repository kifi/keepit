'use strict';

angular.module('kifi.delighted', [])

.directive('kfDelightedSurvey', [
  '$timeout', '$analytics', 'profileService',
  function ($timeout, $analytics, profileService) {
    return {
      restrict: 'A',
      scope: {},
      templateUrl: 'delighted/delightedSurvey.tpl.html',
      link: function (scope, element) {
        scope.delighted = {};
        scope.showSurvey = true;

        function analyticsStageName() {
          if (scope.surveyStage === 'score') {
            return 'npsScore';
          } else if (scope.surveyStage === 'comment') {
            return 'npsComment';
          } else if (scope.surveyStage === 'end') {
            return 'npsThanks';
          } else {
            return null;
          }
        }
        
        scope.surveyStage = 'score';
        $analytics.eventTrack('user_viewed_notification', {
          'source': 'site',
          'type': analyticsStageName()
        });

        scope.$watch('delighted.score', function () {
          if (scope.delighted.score) {
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

        scope.submit = function () {
          profileService.postDelightedAnswer(+scope.delighted.score, scope.delighted.comment || null);
          scope.surveyStage = 'end';
          $analytics.eventTrack('user_viewed_notification', {
            'source': 'site',
            'type': analyticsStageName()
          });
          $timeout(function () {
            hideSurvey();
          }, 3000);
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
          element.find('.kf-delighted-wrap').addClass('hide');
        }
      }
    };
  }
]);
