// @require styles/guide/step_0.css
// @require styles/guide/guide.css
// @require scripts/lib/jquery.js
// @require scripts/keeper.js
// @require scripts/render.js
// @require scripts/html/guide/step_0.js

guide.step0 = guide.step0 || function () {
  var $stage, $pages, $steps;
  return {show: show, remove: removeAll};

  function show($guide, pages) {
    if (!$stage) {
      $stage = $(render('html/guide/step_0', {me: me, pages: pages})).appendTo('body').layout().addClass('kifi-open');
      $steps = $guide.appendTo('body')
        .on('click', '.kifi-guide-x', hide);
      $pages = $stage.find('.kifi-guide-pages')
        .on('click', '.kifi-guide-next', onClickNext)
        .on('click', '.kifi-guide-site-a', onClickSite);
      $(document).data('esc').add(hide);
    }
  }

  function hide() {
    if ($stage) {
      $stage.one('transitionend', remove).removeClass('kifi-open');
      $steps.one('transitionend', remove).removeClass('kifi-showing');
      $stage = $pages = $steps = null;
      $(document).data('esc').remove(hide);
      api.port.emit('end_guide');
    }
  }

  function removeAll() {
    if ($stage) {
      $stage.remove();
      $steps.remove();
      $stage = $pages = $steps = null;
    }
  }

  function onClickNext() {
    $pages.attr('kifi-p', '2');
    $steps.on('transitionend', function end() {
      $(this).off('transitionend', end)
        .data().updateProgress(.2);
    }).addClass('kifi-showing');
  }

  function onClickSite(e) {
    if (e.which === 1) {
      e.preventDefault();
      var url = this.href;
      var siteIdx = $(this).index('.kifi-guide-site-a');
      api.port.emit('await_deep_link', {locator: '#guide/1/' + siteIdx, url: url});
      window.location = url;
    }
  }

  function remove() {
    $(this).remove();
  }
}();
