'use strict';

angular.module('kifi')

.directive('kfYoutube', [

  function () {

    function videoIdToSrc(videoId) {
      return '//www.youtube.com/embed/' + videoId +
        '?rel=0&theme=light&showinfo=0&disablekb=1&modestbranding=1&controls=1&hd=1&autoplay=1&autohide=1&color=white&iv_load_policy=3';
    }

    function videoEmbed(src) {
      return '<iframe width="100%" height="100%" src="' + src + '" frameborder="none" allowfullscreen="true" allowscriptaccess="always"/>';
    }

    function getVideoImage(videoId) {
      return '//img.youtube.com/vi/' + videoId + '/hqdefault.jpg';
    }

    function imageEmbed(src) {
      var videoImg = '<div class="kf-youtube-img" style="background-image:url(' + src + ')"></div>';
      var playImg = '<div class="kf-youtube-play"></div>';
      return videoImg + playImg;
    }

    return {
      restrict: 'A',
      replace: true,
      scope: {
        videoId: '='
      },
      template:
        '<div class="kf-youtube" ng-click="replaceWithVideo()"></div>',
      link: function (scope, element) {

        var lastId = null;

        function updateSrc(videoId) {
          if (lastId === videoId) {
            return;
          }
          lastId = videoId;

          if (videoId) {
            element.html(imageEmbed(getVideoImage(videoId)));
          }
        }

        scope.replaceWithVideo = function() {
          element.html(videoEmbed(videoIdToSrc(lastId)));
        };

        updateSrc(scope.videoId);

        scope.$watch('videoId', updateSrc);
      }
    };
  }
]);
