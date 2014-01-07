// @require styles/keeper/keep_name_prompt.css
// @require scripts/lib/jquery.js
// @require scripts/lib/mustache.js
// @require scripts/render.js
// @require scripts/html/keeper/keep_name_prompt.js

var promptForKeepName = (function () {
  var $box;

  return function () {
    $box = $(render('html/keeper/keep_name_prompt'))
      .appendTo(tile)
      .on('click', '.kifi-knp-x,.kifi-knp-cancel', hide)
      .mousedown(onMouseDown)
      .layout()
      .one('transitionend', function () {
        $box.find('.kifi-knp-input').focus();
      })
      .addClass('kifi-showing');
    $(document).data('esc').add(hide);
  };

  function hide(e) {
    $(document).data('esc').remove(hide);
    if ($box) {
      $box.on('transitionend', $.fn.remove.bind($box, null)).removeClass('kifi-showing');
      $box = null;
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
}());
