!function () {
  var $title = $('.page-title');
  var $logoL = $('.curtain-logo-l');
  var $logoR = $('.curtain-logo-r');
  var openedTimer, closedTimer;

  $('.curtain-action').click(function (e) {
    if (e.which !== 1) return;
    var $form = $('.' + $(this).data('form')).css('display', 'block');
    $title.text($form.data('title'));
    $form.find('input').first().focus();
    openCurtains();
  });
  $('.curtain-back').click(function (e) {
    if (e.which !== 1) return;
    closeCurtains($.fn.hide.bind($('form')));
  });

  function openCurtains() {
    clearTimeout(closedTimer), closedTimer = null;
    var logoL = $logoL[0], wL = logoL.offsetWidth;
    var logoR = $logoR[0], wR = logoR.offsetWidth;
    logoL.style.clip = 'rect(auto ' + wL + 'px auto auto)';
    logoR.style.clip = 'rect(auto auto auto 0)';
    logoL.offsetWidth, logoR.offsetWidth; // force layout
    logoL.style.clip = 'rect(auto ' + Math.round(wL * .33) + 'px auto auto)';
    logoR.style.clip = 'rect(auto auto auto ' + Math.round(wR * .67) + 'px)';
    $('body').addClass('curtains-drawn');
    openedTimer = setTimeout($.fn.hide.bind($([logoL, logoR])), 500);
  }
  function closeCurtains(callback) {
    clearTimeout(openedTimer), openedTimer = null;
    $logoL.add($logoR).css({display: 'block', clip: ''});
    var logoL = $logoL[0], wL = logoL.offsetWidth;
    var logoR = $logoR[0], wR = logoR.offsetWidth;
    logoL.style.clip = 'rect(auto ' + Math.round(wL * .33) + 'px auto auto)';
    logoR.style.clip = 'rect(auto auto auto ' + Math.round(wR * .67) + 'px)';
    logoL.offsetWidth, logoR.offsetWidth; // force layout
    logoL.style.clip = 'rect(auto ' + wL + 'px auto auto)';
    logoR.style.clip = 'rect(auto auto auto 0)';
    $('body').removeClass('curtains-drawn');
    closedTimer = setTimeout(callback, 500);
  }

  $('.form-network.facebook').click(function (e) {
    if (e.which !== 1) return;
    location = 'https://www.facebook.com';
  });
  $('.form-network.linkedin').click(function (e) {
    if (e.which !== 1) return;
    location = 'https://www.linkedin.com';
  });
}();
