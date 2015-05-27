// @require styles/keeper/look_link_broken.css
// @require scripts/lib/jquery.js
// @require scripts/formatting.js
// @require scripts/render.js
// @require scripts/prevent_ancestor_scroll.js
// @require scripts/html/keeper/look_link_broken.js

var showBrokenLookLinkDialog = (function () {
  var $dialog;

  return function (a, authorName, arg) {
    if (typeof arg === 'string') {
      var rangeHtml = formatKifiSelRangeTextAsHtml(arg, 'kifi-llb-dialog-p', 'kifi-llb-dialog-pp');
    } else if (this instanceof HTMLImageElement) {
      var imageUrl = this.src;
      var boxWidth = Math.min(400, Math.max(190, 200 * this.naturalWidth / this.naturalHeight));
    }

    $dialog = $(k.render('html/keeper/look_link_broken', {
      authorName: authorName,
      rangeHtml: rangeHtml,
      imageUrl: imageUrl,
      boxWidth: boxWidth
    }));
    if (rangeHtml) {
      $dialog.find('.kifi-llb-dialog-quote').preventAncestorScroll();
    } else if (imageUrl) {
      this.className = 'kifi-llb-dialog-img';
      $dialog.find('.kifi-llb-dialog-img-a').append(this);
    }

    var $box = $dialog.find('.kifi-llb-dialog-box').css('opacity', 0);
    $dialog.appendTo($('body')[0] || document.documentElement);
    var boxRect = $.extend({}, $box[0].getBoundingClientRect());
    $box.css('margin-top', -boxRect.height / 2);
    boxRect.top -= boxRect.height / 2;
    animatedShow($box, a, boxRect);

    $dialog
      .data('a', a)
      .on('click', '.kifi-llb-dialog-x', hide)
      .mousedown(onMouseDown);
    $(document).data('esc').add(hide);
    api.onEnd.push(hide);
    return hide;
  };

  function hide(e) {
    $(document).data('esc').remove(hide);
    if ($dialog) {
      animatedHide($dialog.find('.kifi-llb-dialog-box'), $dialog.data('a'), $.fn.remove.bind($dialog, null));
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

  function animatedShow($el, fromEl, toRect) {
    var fromRect = fromEl.getClientRects()[0];
    var scale = Math.min(1, fromRect.width / toRect.width);
    $el.css({
      opacity: 0,
      transition: 'none',
      transform: 'translate(' + Math.round(fromRect.left - toRect.left) + 'px,' + Math.round(fromRect.top - toRect.top) + 'px) scale(' + scale + ')'
    })
    .layout()
    .css({
      opacity: 1,
      transform: '',
      transition: ''
    });
  }

  function animatedHide($el, toEl, done) {
    var fromRect = $el[0].getBoundingClientRect();
    var toRect = toEl.getClientRects()[0];
    var scale = Math.min(1, toRect.width / fromRect.width);
    $el.on('transitionend', done).css({
      opacity: 0,
      transform: 'translate(' + Math.round(toRect.left - fromRect.left) + 'px,' + Math.round(toRect.top - fromRect.top) + 'px) scale(' + scale + ')',
      transitionDuration: '.2s'
    });
  }
})();
