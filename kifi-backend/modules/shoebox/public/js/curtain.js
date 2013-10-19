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
    $form.find('.form-email-addr').focus();
    openCurtains();
  });
  $('.curtain-back').click(function (e) {
    if (e.which !== 1) return;
    closeCurtains();
  });

  $('.signup-form').submit(function (e) {
    e.preventDefault();
    var $form = $(this);
    var $body = $('body');
    if (!$body.hasClass('finalizing')) {
      var emailAddr = $form.find('.form-email-addr').val();
      var password = $form.find('.form-password').val();
      // TODO: validation
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
      setTimeout(function () {
        $form.find('.form-first-name').focus();
      }, 200);
    } else {
      var first = $form.find('.form-first-name').val();
      var last = $form.find('.form-last-name').val();
      // TODO: validation
      // TODO: form submission using FormData or falling back to hidden iframe
    }
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

  $('.form-photo-a').click(function (e) {
    if (e.which !== 1) return;
    var $a = $(this);
    if ($a.hasClass('facebook')) {
      window.open('https://www.facebook.com', 'photo', 'width=720,height=400,dialog=yes,menubar=no,resizable=yes,scrollbars=yes,status=yes');
    } else if ($a.hasClass('linkedin')) {
      window.open('https://www.linkedin.com', 'photo', 'width=720,height=400,dialog=yes,menubar=no,resizable=yes,scrollbars=yes,status=yes');
    } else {
      // TODO: upload
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
