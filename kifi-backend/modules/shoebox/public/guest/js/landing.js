$(document).on('click', '.more-arrow', function () {
  var body = document.body;
  var top0 = body.scrollTop;
  var topN = this.offsetTop + this.offsetHeight;
  var px = Math.abs(topN - top0);
  var ms = 320 * Math.log((px + 80) / 60) | 0;
  $(body).animate({scrollTop: topN}, ms);
});

window.addEventListener('message', function (e) {
  if (e.data === 'playing-video') {
    setTimeout(function () {
      var $a = $('<a href="/signup" class="video-signup-btn" data-track-action="clickSignUpVideo">Sign up</a>').css('opacity', 0);
      $('.video-iframe').not(':has(.video-signup-btn)').after($a);
      $a.css('opacity', 1);
    }, 300);
  }
});
