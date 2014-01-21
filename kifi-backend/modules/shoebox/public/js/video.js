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
  }

  function click(e) {
    var $target = $(e.target);
    if ($target.is('.video-popup,.video-x')) {
      e.preventDefault();
      $target.closest('.video-popup')
        .on('transitionend', remove)
        .removeClass('showing');
    }
  }

  function remove() {
    $(this).remove();
  }
});
