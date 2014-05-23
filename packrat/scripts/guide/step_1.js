// @require styles/guide/step_1.css
// @require styles/guide/steps.css
// @require scripts/lib/jquery.js
// @require scripts/keeper.js
// @require scripts/render.js
// @require scripts/guide/spotlight.js
// @require scripts/html/guide/step_1.js
// @require scripts/html/guide/steps.js

var guide = guide || {};
guide.step1 = guide.step1 || function () {
  var $stage, /*$pages,*/ $steps, spotlight, observer;
  return show;

  function show() {
    if (!$stage) {
      spotlight = new Spotlight(wholeWindow(), {opacity: 0});
      var ms = updateSpotlightPosition();
      observer = new MutationObserver(updateSpotlightPosition);
      observer.observe(tile, {childList: true});

      $stage = $(render('html/guide/step_1', me))
        .css('transition-delay', Math.max(0, ms - 200) + 'ms')
        .appendTo('body').layout().addClass('kifi-open');
      $steps = $(render('html/guide/steps', {showing: true}))
        .appendTo('body');
      // $pages = $stage.find('.kifi-guide-pages');
      // $pages.find('.kifi-guide-next').click(next);
      $steps.find('.kifi-guide-steps-x').click(hide);
      $(document).data('esc').add(hide);
      $(window).on('resize', updateSpotlightPosition);
    }
  }

  // function next() {
  //   $pages.attr('kifi-p', '2');
  //   $steps.addClass('kifi-showing');
  // }

  function hide() {
    if ($stage) {
      $stage.one('transitionend', remove).removeClass('kifi-open');
      $steps.one('transitionend', remove).css('transition-delay', '').removeClass('kifi-showing');
      spotlight.animateTo(wholeWindow(), {opacity: 0, detach: true});

      $stage = /*$pages =*/ $steps = spotlight = observer = null;
      $(document).data('esc').remove(hide);
      $(window).off('resize', updateSpotlightPosition);
    }
  }

  function updateSpotlightPosition() {
    var btn = $('.kifi-keep-card')[0];
    var elRect = (btn || tile).getBoundingClientRect(), hPad = btn ? 60 : 40, vPad = btn ? 30 : 20;
    return spotlight.animateTo({
        x: elRect.left - hPad,
        y: elRect.top - vPad,
        w: elRect.width + hPad * 2,
        h: elRect.height + vPad * 2
      }, {opacity: 1});
  }

  function wholeWindow() {
    return {x: 0, y: 0, w: window.innerWidth, h: window.innerHeight};
  }

  function remove() {
    $(this).remove();
  }
}();
