$(function () {
  $('.video-still').click(play).removeAttr('onclick')
    .filter('[data-clicked]').removeAttr('data-clicked').each(play);

  function play() {
    var data = $(this).data();
    if (!data.uri || !data.w || !data.h) return;
    var $popup = $(
      '<div class=video-popup>' +
      '<div class=video-cell>' +
      '<div class=video-width style="max-width:' + data.w + 'px">' +
      '<div class=video-height style="padding-bottom:' + 100 * data.h / data.w + '%">' +
      '<iframe class=video-iframe src="' + data.uri + '" frameborder=0 webkitallowfullscreen mozallowfullscreen allowfullscreen></iframe>' +
      '<a class=video-x href=javascript:></a>' +
      '</div>' +
      '</div>' +
      '</div>' +
      '</div>')
    .appendTo('body');
    $popup[0].offsetHeight;  // force layout
    $popup.addClass('showing');
    $popup.click(click);
    $(document).on('keydown.video-popup-' + $popup[0][$.expando], keydown.bind($popup[0]));
    window.postMessage('playing-video', location.origin);
  }

  function click(e) {
    var $target = $(e.target);
    if ($target.is('.video-popup,.video-x')) {
      e.preventDefault();
      $target.closest('.video-popup').each(hide);
    }
  }

  function keydown(e) {
    if (e.which === 27 && !e.shiftKey && !e.altKey && !e.metaKey && !e.ctrlKey && !e.isDefaultPrevented()) {
      e.preventDefault();
      hide.call(this);
    }
  }

  function hide() {
    $(document).off('keydown.video-popup-' + this[$.expando]);
    $(this).on('transitionend', remove)
      .removeClass('showing');
  }

  function remove() {
    $(this).remove();
  }
});
