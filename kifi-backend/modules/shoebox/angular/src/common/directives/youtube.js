'use strict';

angular.module('kifi')

.directive('kfYoutube', [

  function () {

    function videoIdToSrc(videoId) {
      return '//www.youtube.com/v/' + videoId +
        '&rel=0&theme=light&showinfo=0&disablekb=1&modestbranding=1&controls=1&hd=1&autohide=1&color=white&iv_load_policy=3';
    }

    function videoEmbed(src) {
      return '<embed src="' + src +
        '" type="application/x-shockwave-flash" allowfullscreen="true" style="width:100%; height: 100%;" allowscriptaccess="always"></embed>';
    }

    return {
      restrict: 'A',
      replace: true,
      scope: {
        videoId: '='
      },
      template: '<div class="kf-youtube"></div>',
      link: function (scope, element) {

        var lastId = null;

        function updateSrc(videoId) {
          if (lastId === videoId) {
            return;
          }
          lastId = videoId;

          if (videoId) {
            element.html(videoEmbed(videoIdToSrc(videoId)));
          }
        }

        updateSrc(scope.videoId);

        scope.$watch('videoId', updateSrc);
      }
    };
  }
]);
