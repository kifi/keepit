// @require styles/keeper/look_link_broken.css
// @require scripts/lib/jquery.js
// @require scripts/formatting.js
// @require scripts/render.js
// @require scripts/prevent_ancestor_scroll.js
// @require scripts/html/keeper/look_link_broken.js

var showBrokenLookLinkDialog = (function () {
  var $dialog;

  return function (arg) {
    if (typeof arg === 'string') {
      var rangeHtml = formatKifiSelRangeTextAsHtml(arg, 'kifi-llb-dialog-p', 'kifi-llb-dialog-pp');
    } else if (this instanceof HTMLImageElement) {
      var imageUrl = this.src;
    }

    $dialog = $(render('html/keeper/look_link_broken', {
      rangeHtml: rangeHtml,
      imageUrl: imageUrl
    }));
    if (rangeHtml) {
      $dialog.find('.kifi-llb-dialog-quote').preventAncestorScroll();
    } else if (imageUrl) {
      this.className = 'kifi-llb-dialog-img';
      $dialog.find('.kifi-llb-dialog-img-a').append(this);
    }
    var $box = $dialog.find('.kifi-llb-dialog-box').css('visibility', 'hidden');
    $dialog.appendTo($('body')[0] || document.documentElement);
    $box.css({marginTop: -.5 * $box[0].offsetHeight, visibility: 'visible'});
    $dialog
      .on('click', '.kifi-llb-dialog-x,.kifi-llb-dialog-btn', hide)
      .mousedown(onMouseDown)
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
