// @require styles/guide/step_0.css
// @require styles/guide/steps.css
// @require scripts/lib/jquery.js
// @require scripts/keeper.js
// @require scripts/render.js
// @require scripts/html/guide/step_0.js
// @require scripts/html/guide/steps.js

var guide = guide || {};
guide.step0 = guide.step0 || function () {
  var $stage, $pages, $steps;
  return show;

  function show() {
    if (!$stage) {
      $stage = $(render('html/guide/step_0', me)).appendTo('body').layout().addClass('kifi-open');
      $steps = $(render('html/guide/steps')).appendTo('body');
      $pages = $stage.find('.kifi-guide-pages');
      $pages.find('.kifi-guide-next').click(next);
      $steps.find('.kifi-guide-steps-x').click(hide);
      $(document).data('esc').add(hide);
    }
  }

  function next() {
    $pages.attr('kifi-p', '2');
    $steps.addClass('kifi-showing');
  }

  function hide() {
    if ($stage) {
      $stage.one('transitionend', remove).removeClass('kifi-open');
      $steps.one('transitionend', remove).removeClass('kifi-showing');
      $stage = $pages = $steps = null;
      $(document).data('esc').remove(hide);
    }
  }

  function remove() {
    $(this).remove();
  }
}();
