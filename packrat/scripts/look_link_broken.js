// @require styles/keeper/look_link_broken.css
// @require scripts/lib/jquery.js
// @require scripts/lib/mustache.js
// @require scripts/render.js
// @require scripts/html/keeper/look_link_broken.js

var showBrokenLookLinkDialog = (function () {
  var $dialog;

  return function (tooNew) {
    var ua = navigator.userAgent;
    $dialog = $(render('html/keeper/look_link_broken', {
        tooNew: tooNew,
        chrome: ua.indexOf('Chrom') >= 0,
        firefox: ua.indexOf('Firefox/') >= 0
      }))
      .appendTo($('body')[0] || document.documentElement)
      .on('click', '.kifi-llb-dialog-x,.kifi-llb-dialog-btn', hide)
      .mousedown(onMouseDown)
      .layout()
      .addClass('kifi-showing');
    $dialog.find('.kifi-llb-dialog-btn').focus();
    $(document).data('esc').add(hide);
  };

  function hide(e) {
    $(document).data('esc').remove(hide);
    if ($dialog) {
      $dialog.on('transitionend', $.fn.remove.bind($dialog, null)).removeClass('kifi-showing');
      $dialog = null;
      if (e) {
        e.preventDefault();
      }
    }
  }

  function onMouseDown(e) {
    if (e.target === this) {
      hide();
    }
  }
})();
