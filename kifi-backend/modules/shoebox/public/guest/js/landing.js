$(document).on('click', '.more-arrow', function () {
  var $html = $('html');
  var top0 = $html.prop('scrollTop');
  var topN = this.offsetTop + this.offsetHeight;
  var px = Math.abs(topN - top0);
  var ms = 320 * Math.log((px + 80) / 60) | 0;
  $html.animate({scrollTop: topN}, ms);
});

window.addEventListener('message', function (e) {
  if (e.data === 'playing-video') {
    setTimeout(function () {
      var $a = $('<a href="/signup" class="video-signup-btn" data-track-action="clickSignUpVideo">Sign up</a>')
        .css({opacity: 0, transition: '1s ease-out'});
      $('.video-iframe').not(':has(.video-signup-btn)').after($a);
      $a.on('transitionend', function () {$(this).removeAttr('style')})
        .each(function () {this.offsetHeight})
        .css('opacity', 1);
    }, 300);
  }
});
