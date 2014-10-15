$(function () {
  var $hero = $('.hero');
  setInterval(function () {
    var n = 6, i = n;
    try {
      i = $hero[0].className.match(/\bstate_(\d)/)[1];
    } catch (e) {
    }
    $hero.removeClass('state_' + i).addClass('state_' + (i + 1) % n);
  }, 4000);
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
