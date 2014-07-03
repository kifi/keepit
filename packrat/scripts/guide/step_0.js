// @require styles/guide/step_0.css
// @require styles/guide/guide.css
// @require scripts/lib/jquery.js
// @require scripts/keeper.js
// @require scripts/render.js
// @require scripts/html/guide/step_0.js

guide.step0 = guide.step0 || function () {
  var $stage, $steps;
  var eventsToBlock = ['mousewheel','wheel'];
  return {show: show, remove: removeAll};

  function show($guide, pages, __, allowEsc) {
    if (!$stage) {
      $stage = $(render('html/guide/step_0', {me: me, pages: pages})).appendTo('body').layout().addClass('kifi-open');
      $steps = $guide.appendTo('body')
        .on('click', '.kifi-guide-x', hide);
      $stage.find('.kifi-guide-pages')
        .on('click', '.kifi-guide-0-next', onClickNext)
        .on('click', '.kifi-guide-site-a', onClickSite);
      if (allowEsc) {
        $(document).data('esc').add(hide);
      }
      api.port.emit('track_guide', [0, 0]);
      eventsToBlock.forEach(function (type) {
        window.addEventListener(type, blockEvent, true);
      });
    }
  }

  function hide() {
    if ($stage) {
      $stage.one('transitionend', remove).removeClass('kifi-open');
      $steps.one('transitionend', remove).removeClass('kifi-showing');
      api.port.emit('end_guide', [0, +$stage.find('.kifi-guide-pages').attr('kifi-p')]);
      $stage = $steps = null;
      $(document).data('esc').remove(hide);
      eventsToBlock.forEach(function (type) {
        window.removeEventListener(type, blockEvent, true);
      });
    }
  }

  function removeAll() {
    if ($stage) {
      $stage.remove();
      $steps.remove();
      $stage = $steps = null;
    }
  }

  function onClickNext() {
    $stage.find('.kifi-guide-pages').attr('kifi-p', '1');
    $steps.on('transitionend', function end() {
      $(this).off('transitionend', end)
        .data().updateProgress(.2);
    }).addClass('kifi-showing');
    api.port.emit('track_guide', [0, 1]);
  }

  function onClickSite(e) {
    if (e.which === 1) {
      var url = this.href;
      var siteIdx = $(this).index('.kifi-guide-site-a');
      api.port.emit('await_deep_link', {locator: '#guide/1/' + siteIdx, url: url});
      api.port.emit('track_guide_choice', siteIdx);
      window.location.href = url;
    }
  }

  function blockEvent(e) {
    e.preventDefault();
    e.stopImmediatePropagation();
  }

  function remove() {
    $(this).remove();
  }
}();
