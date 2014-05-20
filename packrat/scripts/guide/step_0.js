// @require styles/guide/step_0.css
// @require scripts/lib/jquery.js
// @require scripts/keeper.js
// @require scripts/render.js
// @require scripts/html/guide/step_0.js

var guide = guide || {};
guide.step0 = guide.step0 || function () {
  var $stage, $pages;
  return show;

  function show() {
    if (!$stage) {
      $stage = $(render('html/guide/step_0', me)).appendTo('body').layout().addClass('kifi-open');
      $pages = $stage.find('.kifi-guide-pages');
      $pages.find('.kifi-guide-next').click(next);
      $(document).data('esc').add(hide);
    }
  }

  function hide() {
    if ($stage) {
      $stage.one('transitionend', remove).removeClass('kifi-open');
      $stage = $pages = null;
      $(document).data('esc').remove(hide);
    }
  }

  function next() {
    $pages.one('transitionend', function () {
      // $pages.find('.kifi-guide-site-a').focus();
    }).attr('kifi-p', '2');
  }

  function remove() {
    $(this).remove();
  }
}();
