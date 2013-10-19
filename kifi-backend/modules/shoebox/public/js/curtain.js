$.postJson = function(uri, data) {
  return $.ajax({
    url: uri,
    type: 'POST',
    dataType: 'json',
    data: JSON.stringify(data),
    contentType: 'application/json'
  });
};

!function () {
  $.fn.layout = function() {
    return this.each(forceLayout);
  };
  function forceLayout() {
    this.clientHeight;
  }
}();

!function () {
  var $logoL = $('.curtain-logo-l');
  var $logoR = $('.curtain-logo-r');

  $('.curtain-action').click(function (e) {
    if (e.which !== 1) return;
    var $form = $('form').hide()
      .filter('.' + $(this).data('form'))
      .css('display', 'block');
    $('.page-title').text($form.data('title'));
    $form.find('input').first().focus();
    openCurtains();
  });
  $('.curtain-back').click(function (e) {
    if (e.which !== 1) return;
    closeCurtains();
  });

  $('.signup-form').submit(function (e) {
    e.preventDefault();
    var $form = $(this);
    var emailAddr = $form.find('.form-email-addr').val();
    var password = $form.find('.form-password').val();
    // $.postJson('/some/sign/up/path', {
    //   e: emailAddr,
    //   p: password
    // }).done(function () {
    //
    // }).fail(function () {
    //
    // });
    $('.finalize-email-addr').text(emailAddr);
    transitionTitle($form.data('title2'));
    $('body').addClass('finalizing');
  });
  $('.login-form').submit(function (e) {
    e.preventDefault();
    // authenticate via XHR (users on browsers w/o XHR support have no reason to log in)
    // $.postJson('/some/log/in/path', {
    //   e: $form.find('.form-email-addr').val(),
    //   p: $form.find('.form-password').val()
    // }).done(function () {
         location = '/';
    // }).fail(function () {
    //   TODO: highlight incorrect email address or password or show connection or generic error message
    // });
  });

  $('.form-network').click(function (e) {
    if (e.which !== 1) return;
    var $a = $(this);
    var $form = $a.closest('form');
    var network = ['facebook', 'linkedin'].filter($.fn.hasClass.bind($a))[0];
    if ($form.hasClass('signup-form')) {
      if (network === 'facebook') {
        location = 'https://www.facebook.com';
      } else if (network === 'linkedin') {
        location = 'https://www.linkedin.com';
      }
    } else if ($form.hasClass('login-form')) {
      if (network === 'facebook') {
        location = 'https://www.facebook.com';
      } else if (network === 'linkedin') {
        location = 'https://www.linkedin.com';
      }
    }
  });

  function openCurtains() {
    var logoL = $logoL[0], wL = logoL.offsetWidth;
    var logoR = $logoR[0], wR = logoR.offsetWidth;
    logoL.style.clip = 'rect(auto ' + wL + 'px auto auto)';
    logoR.style.clip = 'rect(auto auto auto 0)';
    logoL.offsetWidth, logoR.offsetWidth; // force layout
    logoL.style.clip = 'rect(auto ' + Math.round(wL * .33) + 'px auto auto)';
    logoR.style.clip = 'rect(auto auto auto ' + Math.round(wR * .67) + 'px)';
    $('body').addClass('curtains-drawn');
  }
  function closeCurtains() {
    $logoL.add($logoR).css({display: 'block', clip: ''});
    var logoL = $logoL[0], wL = logoL.offsetWidth;
    var logoR = $logoR[0], wR = logoR.offsetWidth;
    logoL.style.clip = 'rect(auto ' + Math.round(wL * .33) + 'px auto auto)';
    logoR.style.clip = 'rect(auto auto auto ' + Math.round(wR * .67) + 'px)';
    logoL.offsetWidth, logoR.offsetWidth; // force layout
    logoL.style.clip = 'rect(auto ' + wL + 'px auto auto)';
    logoR.style.clip = 'rect(auto auto auto 0)';
    $('body').removeClass('curtains-drawn');
  }

  function transitionTitle(text) {
    $('.page-title.obsolete').remove();
    var $title = $('.page-title');
    $title.after($title.clone().text(text)).addClass('obsolete').layout();
  }
}();
