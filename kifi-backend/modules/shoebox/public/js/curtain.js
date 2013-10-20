
!function () {
  'use strict';
  $.postJson = function(uri, data) {
    return $.ajax({
      url: uri,
      type: 'POST',
      dataType: 'json',
      data: JSON.stringify(data),
      contentType: 'application/json'
    });
  };

  $.fn.layout = function() {
    return this.each(forceLayout);
  };
  function forceLayout() {
    this.clientHeight;
  }
}();

!function () {
  'use strict';
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
      // TODO: allow photo upload to complete if in progress
      window.location = '/';
    }
  });
  $('.login-form').submit(function (e) {
    e.preventDefault();
    // authenticate via XHR (users on browsers w/o XHR support have no reason to log in)
    // $.postJson('/some/log/in/path', {
    //   e: $form.find('.form-email-addr').val(),
    //   p: $form.find('.form-password').val()
    // }).done(function () {
         window.location = '/';
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
        window.location = 'https://www.facebook.com';
      } else if (network === 'linkedin') {
        window.location = 'https://www.linkedin.com';
      }
    } else if ($form.hasClass('login-form')) {
      if (network === 'facebook') {
        window.location = 'https://www.facebook.com';
      } else if (network === 'linkedin') {
        window.location = 'https://www.linkedin.com';
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
    }
  });
  $('.form-photo-file').change(function (e) {
    if (this.files) {
      var f = this.files[0];
      if (f && ~f.type.search(/^image\/(?:jpeg|png|gif)$/)) {
        $('.form-photo').css({'background-image': 'url(' + URL.createObjectURL(f) + ')', 'background-size': 'cover'});
        uploadPhotoXhr2(f);
      } else {
        $('.form-photo').css({'background-image': '', 'background-size': ''});
      }
    } else {
      uploadPhotoIframe(this.form);
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

  function uploadPhotoXhr2(file) {
    var xhr = new XMLHttpRequest();
    if (xhr.upload) {
      xhr.upload.addEventListener('progress', function (e) {
        if (e.lengthComputable) {
          setPhotoProgress(e.loaded / e.total);
        }
      });
      xhr.upload.addEventListener('load', setPhotoProgress.bind(null, 1));
      setPhotoProgress(0);
    }
    xhr.open('POST', 'https://www.kifi.com/testing/upload', true);
    xhr.send(file);
  }
  function uploadPhotoIframe(form) {
    $('.form-photo').css('background-image', 'none');
    $('iframe[name=upload]').remove();  // TODO: cleaner cancellation of any in-progress upload?
    $('<iframe name=upload>').hide().appendTo('body').load(function() {
      clearTimeout(fakePhotoProgressTimer);
      setPhotoProgress(1);
      var $iframe = $(this), o;
      try {
        o = $.parseJSON($iframe.contents().find('body').text());
      } catch (err) {
        console.error('[uploadPhotoIframe]', err);
      }
      $('.form-photo').css('background-image', o ? 'url(' + o.url + ')' : '');
      $(form).removeAttr('method target action');
    });
    form.method = 'POST';
    form.target = 'upload';
    form.action = 'https://www.kifi.com/testing/upload';
    form.submit();
    clearTimeout(fakePhotoProgressTimer);
    fakePhotoProgress(0, 100);
  }
  var fakePhotoProgressTimer;
  function fakePhotoProgress(frac, ms) {
    setPhotoProgress(frac);
    fakePhotoProgressTimer = setTimeout(fakePhotoProgress.bind(null, 1 - (1 - frac) * .9, ms * 1.1), ms);
  }
  var progressBar = $('.form-photo-progress')[0];
  function setPhotoProgress(frac) {
    var pct = Math.round(frac * 100);
    progressBar.style.borderWidth = frac < 1 ? '0 ' + (100 - pct) + 'px 0 ' + pct + 'px' : '';
  }
}();
