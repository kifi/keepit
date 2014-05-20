// @require styles/keeper/keep_name_prompt.css
// @require scripts/lib/jquery.js
// @require scripts/render.js
// @require scripts/title_from_url.js
// @require scripts/html/keeper/keep_name_prompt.js

var promptForKeepName = (function () {
  var $box, $input, $save;

  return function (parent, onHide) {
    if ($box) {
      log('[promptForKeepName] already showing');
      return;
    }
    log('[promptForKeepName]');
    $box = $(render('html/keeper/keep_name_prompt'))
      .data('onHide', onHide)
      .appendTo(parent)
      .on('click', '.kifi-knp-x,.kifi-knp-cancel', hide)
      .on('click', '.kifi-knp-save[href]', save)
      .layout()
      .one('transitionend', onShown)
      .addClass('kifi-showing');
    $input = $box.find('.kifi-knp-input').val(formatTitleFromUrl(document.URL)).keydown(inputKeyDown);
    $save = $box.find('.kifi-knp-save');
    $(document).data('esc').add(hide);
    document.addEventListener('mousedown', docMouseDown, true);
    if (window.pane) {
      pane.shade();
    }
  };

  function onShown() {
    $input.focus().select();
  }

  function save() {
    var name = $input.val().trim();
    if (name) {
      $input.prop('disabled', true);
      api.port.emit('set_title', withUrls({title: name}), function (success) {
        log('[save]', success ? 'success' : 'error');
        $input.prop('disabled', false);
        clearTimeout(progressTimeout), progressTimeout = null;
        if (success) {
          $save.addClass('kifi-done');
          setTimeout(hide, 1600);
        } else {
          $save.attr('href', 'javascript:').one('transitionend', function () {
            $progress.css('width', 0);
            $save.removeClass('kifi-fail');
          }).addClass('kifi-fail');
        }
      });
      $save.removeAttr('href');
      var $progress = $save.find('.kifi-knp-save-progress');
      updateProgress.call($progress[0], 0);
    } else {
      $input.focus().select();
    }
  }

  function hide(e) {
    $(document).data('esc').remove(hide);
    document.removeEventListener('mousedown', docMouseDown, true);
    clearTimeout(progressTimeout), progressTimeout = null;
    if ($box) {
      $box.on('transitionend', $.fn.remove.bind($box, null)).removeClass('kifi-showing');
      $box.data('onHide')();
      $box = $input = $save = null;
      if (e) {
        e.preventDefault();
      }
      if (window.pane) {
        pane.unshade();
      }
    }
  }

  function docMouseDown(e) {
    if (!$box[0].contains(e.target)) {
      hide();
    }
  }

  function inputKeyDown(e) {
    if (e.which === 13) {
      save();
    }
  }

  var progressTimeout;
  function updateProgress(frac) {
    log('[updateProgress]', frac);
    this.style.width = Math.min(frac * 100, 100) + '%';
    var fracLeft = .9 - frac;
    if (fracLeft > .0001) {
      progressTimeout = setTimeout(updateProgress.bind(this, frac + .06 * fracLeft), 10);
    }
  }
}());
