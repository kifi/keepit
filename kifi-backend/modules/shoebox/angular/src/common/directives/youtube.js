'use strict';

angular.module('kifi')

.directive('kfYoutube', [
  function () {
    function imageEmbed(videoId) {
      return [
        '<div class="kf-youtube-img" style="background-image:url(//img.youtube.com/vi/',
         videoId, '/hqdefault.jpg)" click-action="playedYoutubeVideo"></div>',
        '<div class="kf-youtube-play" click-action="playedYoutubeVideo"></div>'
      ].join('');
    }

    function videoEmbed(videoId) {
      return [
        '<iframe width="100%" height="100%" src="//www.youtube.com/embed/', videoId,
        '?rel=0&theme=light&showinfo=0&disablekb=1&modestbranding=1&controls=1&hd=1&autoplay=1&autohide=1&color=white&iv_load_policy=3" ',
        'frameborder="none" allowfullscreen="true" allowscriptaccess="always"/>'
      ].join('');
    }

    return {
      restrict: 'A',
      replace: true,
      scope: {
        videoId: '='
      },
      template: '<div class="kf-youtube" ng-click="insertVideo()"></div>',
      link: function (scope, element) {
        scope.insertVideo = function() {
          element.html(videoEmbed(scope.videoId)).addClass('kf-with-video');
        };

        scope.$watch('videoId', function (videoId) {
          if (videoId) {
            element.html(imageEmbed(videoId)).removeClass('kf-with-video');
          }
        });
      }
    };
  }
]);
