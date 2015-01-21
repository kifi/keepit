(function () {
  'use strict';
  $.postJson = function (uri, data) {
    return $.ajax({
      url: uri,
      type: 'POST',
      dataType: 'json',
      data: JSON.stringify(data),
      contentType: 'application/json'
    });
  };

  $.fn.layout = function () {
    return this.each(forceLayout);
  };
  function forceLayout() {
    this.offsetHeight;
  }

  $.fn.reset = function () {
    return this.each(reset);
  };
  function reset() {
    this.reset();
  }
}());

var kifi = {};
kifi.form = (function () {
  'use strict';
  var emailAddrRe = /^[a-zA-Z0-9.!#$%&'*+\/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+$/;
  return {
    showError: function ($in, msg) {
      var $err = $('<div class=form-error>').css('visibility', 'hidden').html(msg).appendTo('body')
        .position({my: 'left top', at: 'left bottom+10', of: $in, collision: 'fit none'})
        .css('visibility', '');
      $in.blur();  // closes browser autocomplete suggestion list
      $in.focus().select().on('input blur', removeError);
      return $err;
      function removeError() {
        $err.remove();
        $in.off('input blur', removeError);
      }
    },
    validateEmailAddress: function ($in) {
      var s = $.trim($in.val());
      if (!s) {
        Tracker.track('visitor_viewed_page', { error: 'noEmail' });
        kifi.form.showError($in, 'Please enter your email address');
      } else if (!emailAddrRe.test(s)) {
        Tracker.track('visitor_viewed_page', { error: 'invalidEmail', errorValue: s });
        kifi.form.showError($in, 'Invalid email address');
      } else {
        return s;
      }
    },
    validateNewPassword: function ($in) {
      var s = $in.val();
      if (!s) {
        Tracker.track('visitor_viewed_page', { error: 'noNewPassword'});
        kifi.form.showError($in, 'Please choose a password<br>for your account');
      } else if (s.length < 7) {
        Tracker.track('visitor_viewed_page', { error: 'shortNewPassword', errorValue: s.length });
        kifi.form.showError($in, 'Password must be at least 7 characters');
      } else {
        return s;
      }
    },
    validatePassword: function ($in) {
      var s = $in.val();
      if (!s) {
        Tracker.track('visitor_viewed_page', { error: 'noPassword' });
        kifi.form.showError($in, 'Please enter your password');
      } else if (s.length < 7) {
        Tracker.track('visitor_viewed_page', { error: 'shortPassword', errorValue: s.length });
        kifi.form.showError($in, 'Password too short');
      } else {
        return s;
      }
    },
    validateName: function ($in) {
      var s = $.trim($in.val());
      if (!s) {
        Tracker.track('visitor_viewed_page', { error: 'noName' });
        kifi.form.showError($in,
          '<div class=form-error-title>Name is required</div>' +
          '<div class=form-error-explanation>We need your name so that<br>your friends will be able to<br>communicate with you</div>');
      } else {
        return s;
      }
    }
  };
}());

(function () {
  'use strict';

  $('body').removeClass('still');

  $('.curtain-action').on('mousedown click', function (e) {
    if (e.which !== 1 || $('body').hasClass('curtains-drawn')) return;
    var isLogin = $(this).hasClass('curtain-login');
    var $signup = $('.signup').css('display', isLogin ? 'none' : 'block');
    var $login = $('.login').css('display', !isLogin ? 'none' : 'block');
    $('html').data('trackType', isLogin ? 'login' : 'signup');
    var $form = isLogin ? $login : $('.signup-1');
    $('.page-title').text($form.data('title')).layout();
    $('body').addClass('curtains-drawn');
    setTimeout($.fn.focus.bind($form.find('.form-email-addr')), 100);
  });
  $('.curtain-back').on('mousedown click', function (e) {
    if (e.which !== 1 || e.type === 'click' && (e.pageX || e.pageY)) return;
    $signup1Form.reset();
    $loginForm.reset();
    var ms = 1000 * $signup2EmailForm.css('transition-duration').split('s')[0];
    var $body = $('body');
    if (!$body.hasClass('finalizing')) {
      $('.page-title').removeClass('returned');
      $body.removeClass('curtains-drawn');
    } else {
      transitionTitle();
      $body.removeClass('finalizing');
      $.post($(this).data('cancelUri'));
      $('.signup-1').show();
      $('html').data('trackType', 'signup');
      setTimeout(function() {
        $signup2EmailForm.hide();
        $signup2SocialForm.hide();
        $('.signup-resume').remove();
        $signup1Form.find('.form-email-addr').focus();
      }, ms);
    }
  });

  $('.cancel-signup a').on('mousedown click', function(e) {
    Tracker.trackClick(this);
    e.preventDefault();
    if ($('body').hasClass('finalizing')) {
      $.post($(this).data('cancelUri'), function(e) {
        window.location = "/";
      });
    } else {
      window.location = "/";
    }
    return false;
  });

  $('.cancel-signup-login').on('mousedown click', function(e) {
    Tracker.trackClick(this);
    e.preventDefault();
    if ($('body').hasClass('finalizing')) {
      $.post($(this).data('cancelUri'), function(e) {
        window.location = "/login";
      });
    } else {
      window.location = "/login";
    }
    return false;
  });

  function getParameterByName(name) {
    name = name.replace(/[\[]/, "\\[").replace(/[\]]/, "\\]");
    var regex = new RegExp("[\\?&]" + name + "=([^&#]*)"),
        results = regex.exec(location.search);
    return results === null ? "" : decodeURIComponent(results[1].replace(/\+/g, " "));
  }

  if (getParameterByName('twitter')) { // do not show linkedIn (if twitter is a url query param)
    var linkedInButtons = $('.form-network.linkedin');
    for (var i=0; i < linkedInButtons.length; i++) {
      linkedInButtons.eq(i).hide();
    }
  } else { // do not show twitter
    var twitterButtons = $('.form-network.twitter');
    for (var i=0; i < twitterButtons.length; i++) {
      twitterButtons.eq(i).hide();
    }
  }

  var animateButton = function (button) {
    var $button = $(button);
    var $progress = $button.find('.progress');
    var $text = $button.find('.text');

    $button.prop('disabled', true);
    updateProgress.call($progress[0], 0);

    var progressTimeout;
    function updateProgress(frac) {
      if (this) {
        this.style.width = Math.min(frac * 100, 100) + '%';
        if (frac < 1) {
          var delta = Math.min(.01 * (.9 - frac), 0.005);
          if (delta > 0.0001) {
            progressTimeout = setTimeout(updateProgress.bind(this, frac + delta), 10);
          }
        }
      }
    }

    return {
      update: function(newProgress) {
        updateProgress.call($progress[0], newProgress);
      },
      fail: function() {
        clearTimeout(progressTimeout), progressTimeout = null;
        $progress.css('width', 0);
        $button.prop('disabled', false);
      },
      success: function() {
        clearTimeout(progressTimeout), progressTimeout = null;
        $button.addClass('submit-done');
      }
    };
  }

  var $signup1Form = $('.signup-1').submit(function (e) {
    e.preventDefault();
    var $form = $(this);
    var animation = animateButton($form.find('.form-submit-sexy'));
    var promise = $form.data('promise');
    if (promise && promise.state() === 'pending') {
      return false;
    }
    $('.form-error').remove();
    var $email = $form.find('.form-email-addr');
    var $password = $form.find('.form-password');
    var email = kifi.form.validateEmailAddress($email);
    var password = email && kifi.form.validateNewPassword($password);
    if (email && password) {
      Tracker.trackClick($form.find('button')[0]);
      $form.data('promise', $.postJson(this.action, {
        email: email,
        password: password
      }).done(function (data) {
        Tracker.track($('html').data('trackViewEvent'));
        animation.update(1);
        animation.success();
        if (!navigate(data)) {
          transitionTitle($signup2EmailForm.data('title'));
          $signup2EmailForm.css('display', 'block').layout();
          $('body').addClass('finalizing droppable');
          $('.signup-1').hide();
          $('html').attr('data-track-type', 'signup2Email')
          setTimeout($.fn.focus.bind($('.form-first-name')), 100);
        }
      }).fail(function (xhr) {
        animation.fail();
        var o = xhr.responseJSON;
        if (o && o.error === 'user_exists_failed_auth') {
          Tracker.track('visitor_viewed_page', { error: 'incorrectPassword' });
          kifi.form.showError($password, 'Account exists, incorrect password');
        } else if (o && o.error === 'error.email') {
          Tracker.track('visitor_viewed_page', { error: 'errorEmail' });
          kifi.form.showError($password, 'Error With Email Format');
        } else {
          Tracker.track('visitor_viewed_page', { error: 'unknownSignupError' });
          kifi.form.showError($password, 'Signup Error');
        }
      }));
    } else {
      animation.fail();
    }
    return false;
  });
  function transitionTitle(text) {
    var $title = $('.page-title');
    var $obsolete = $title.filter('.obsolete');
    var $abandoned = $title.filter('.abandoned').remove();
    $title = $title.not($obsolete).not($abandoned);
    if (text) {
      $obsolete.remove();
      $title.removeClass('returned').after($title.clone().text(text)).addClass('obsolete').layout();
    } else {
      $title.addClass('abandoned');
      $obsolete.removeClass('obsolete').addClass('returned').layout();
    }
  }
  function navigate(data) {
    if (data.uri) {
      window.location = data.uri;
      return true;
    }
  }

  var $signup2EmailForm = $('.signup-2-email').submit(function (e) {
    e.preventDefault();
    var $form = $(this);
    var animation = animateButton($form.find('.form-submit-sexy'));
    var promise = $form.data('promise');
    if (promise && promise.state() === 'pending') {
      return false;
    }
    $('.form-error').remove();
    var first = kifi.form.validateName($form.find('.form-first-name'));
    var last = first && kifi.form.validateName($form.find('.form-last-name'));
    if (first && last) {
      var pic = $photo.data();
      $form.data('promise', $.when(pic.uploadPromise).always(function (upload) {
          animation.update(50)
         Tracker.trackClick($form.find('button')[0]);
         $form.data('promise', $.postJson($form.attr('action'), {
          firstName: first,
          lastName: last,
          picToken: upload && upload.token,
          picWidth: pic.width,
          picHeight: pic.height,
          cropX: pic.x,
          cropY: pic.y,
          cropSize: pic.size
        })
        .fail(function() {
          animation.fail();
        })
        .done(function() {
          animation.success();
        })
        .done(navigate));
      }));
    } else {
      animation.fail();
    }
    return false;
  });
  var $signup2SocialForm = $('.signup-2-social').submit(function (e) {
    e.preventDefault();
    var $form = $(this);
    var animation = animateButton($form.find('.form-submit-sexy'));
    var promise = $form.data('promise');
    if (promise && promise.state() === 'pending') {
      return false;
    }
    $('.form-error').remove();
    var $email = $form.find('.social-email');
    var email = kifi.form.validateEmailAddress($email);
    var password = Math.random().toString(36).substring(8);

    if (email) {
      Tracker.trackClick($form.find('button')[0]);
      $form.data('promise', $.postJson(this.action, {
        firstName: $form.data('first'),
        lastName: $form.data('last'),
        email: email,
        password: password
      })
      .done(function(resp) {
        if ($('html').data('kifi-ext')) {
          setTimeout(function() {
            animation.success();
            navigate(resp);
          }, 3000);
        } else {
          animation.success();
          navigate(resp);
        }
      })
      .fail(function (xhr) {
        animation.fail();
        var o = xhr.responseJSON;
        if (o && o.error === 'known_email_address') {
          $form.data('email', email);
          Tracker.track('visitor_viewed_page', { error: 'knownEmailAddress', errorValue: email });
          kifi.form.showError(
            $email,
            'A Kifi account already uses<br>this email address. <a href=javascript: class=social-claim-account>Claim it</a>')
          .on('mousedown', onClaimAccountClick.bind(null, email));
        }
      }));
    } else {
      animation.fail();
    }
    return false;
  }).on('click', '.social-change-email', function (e) {
    if (e.which !== 1) return;
    $('.social-email').removeAttr('disabled').focus().select();
    $(this).addClass('clicked');
  })
  function onClaimAccountClick(emailAddr, e) {
    if (e.which !== 1) return;
    claimAccountDialog.show(emailAddr);
  }

  var $loginForm = $('.login').submit(onLoginFormSubmit)
  .on('click', '.password-forgot', function (e) {
    if (e.which !== 1) return;
    resetPasswordDialog.show($(this).closest('form').find('.form-email-addr').val());
  });
  function onLoginFormSubmit(e) {
    e.preventDefault();
    var $form = $(this);
    var promise = $form.data('promise');
    if (promise && promise.state() === 'pending') {
      return false;
    }
    $('.form-error').remove();
    var $email = $form.find('.form-email-addr');
    var $password = $form.find('.form-password');
    var email = kifi.form.validateEmailAddress($email);
    var password = email && kifi.form.validatePassword($password);
    if (email && password) {
      Tracker.trackClick($form.find('button')[0]);
      $form.data('promise', $.postJson(this.action, {
        username: email,
        password: password
      })
      .done(navigate)
      .fail(function (xhr) {
        if (xhr.status === 403) {
          var o = xhr.responseJSON;
          if (o.error === 'no_such_user') {
            Tracker.track('visitor_viewed_page', { error: 'noSuchUser', errorValue: email });
            kifi.form.showError($email, 'No account with this email address');
          } else {
            Tracker.track('visitor_viewed_page', { error: 'incorrectPassword', errorValue: email });
            kifi.form.showError($password,
              '<div class=form-error-title>Incorrect password</div>' +
              '<div class=form-error-explanation>To choose a new password,<br>click “I forgot”.</div>');
          }
        } else {
          Tracker.track('visitor_viewed_page', { error: 'signinFail', errorValue: s.length });
          // TODO: offline? 500?
        }
      }));
    }
    return false;
  }

  var URL = window.URL || window.webkitURL;
  var $photo = $('.form-photo');
  $('.form-photo-a').click(function (e) {
    if (e.which !== 1) return;
    var $a = $(this), w = 880, h = 460;
    var top = (window.screenTop || window.screenY || 0) + Math.round(.5 * (window.innerHeight - h));
    var left = (window.screenLeft || window.screenX || 0) + Math.round(.5 * (window.innerWidth - w));
    window.open($a.data('uri'), 'photo', 'width=' + w + ',height=' + h + ',top=' + top + ',left=' + left + ',dialog=yes,menubar=no,resizable=yes,scrollbars=no,status=no');
    window.afterSocialLink = afterSocialLink.bind($a.closest('form')[0]);
  });
  $('.form-photo-file').change(function () {
    if (this.files && URL) {
      var upload = uploadPhotoXhr2(this.files);
      if (upload) {
        // wait for file dialog to go away before starting dialog transition
        setTimeout(showLocalPhotoDialog.bind(null, upload), 200);
      }
    } else {
      uploadPhotoIframe(this.form);
    }
  });
  $('.form-photo-file-a').click(function (e) {
    if (e.which === 1) {
      $('.form-photo-file').click();
    }
  });
  $(document).on('dragenter dragover drop', '.droppable', function (e) {
    if (~Array.prototype.indexOf.call(e.originalEvent.dataTransfer.types, 'Files')) {
      if (e.type === 'dragenter') {
        $drop.css('display', 'block');
        $(document).on('mousemove.drag', removeDropTarget);
      } else if (e.type === 'drop') {
        removeDropTarget();
        var upload = uploadPhotoXhr2(e.originalEvent.dataTransfer.files);
        if (upload) {
          showLocalPhotoDialog(upload);
        }
      }
      return false;
    }
  });
  var $drop = $('.form-photo-drop').on('dragenter dragleave', function (e) {
    $drop.toggleClass('over', e.type === 'dragenter');
  });
  function removeDropTarget() {
    $drop.css('display', '');
    $(document).off('mousemove.drag');
  }
  function afterSocialLink(firstName, lastName, photoUrl) {
    var $form = $(this);
    var $first = $form.find('.form-first-name');
    var $last = $form.find('.form-last-name');
    if (firstName && !$.trim($first.val())) {
      $first.val(firstName);
    }
    if (lastName && !$.trim($last.val())) {
      $last.val(lastName);
    }
    if (photoUrl) {
      if (photoXhr2) {
        photoXhr2.abort();
      }
      $photo.css({'background-image': 'url(' + photoUrl + ')', 'background-position': '', 'background-size': ''}).removeClass('unset');
    }
  }

  var photoXhr2;
  function uploadPhotoXhr2(files) {
    var file = Array.prototype.filter.call(files, isImage)[0];
    if (file) {
      if (photoXhr2) {
        photoXhr2.abort();
      }
      var xhr = photoXhr2 = new XMLHttpRequest();
      var deferred = createPhotoUploadDeferred();
      xhr.upload.addEventListener('progress', function (e) {
        if (e.lengthComputable) {
          deferred.notify(e.loaded / e.total);
        }
      });
      xhr.addEventListener('load', function () {
        deferred.resolve(JSON.parse(xhr.responseText));
      });
      xhr.addEventListener('loadend', function () {
        if (photoXhr2 === xhr) {
          photoXhr2 = null;
        }
        if (deferred.state() === 'pending') {
          deferred.reject();
        }
      });
      xhr.open('POST', $photo.data('uri'), true);
      xhr.send(file);
      return {file: file, promise: deferred.promise()};
    }
  }
  function isImage(file) {
    return file.type.search(/^image\/(?:jpeg|png|gif)$/) === 0;
  }

  var iframeDeferred;
  function uploadPhotoIframe(form) {
    $photo.css('background-image', 'none').addClass('unset');

    if (iframeDeferred) {
      iframeDeferred.reject();  // clean up previous in-progress upload
    }
    var deferred = iframeDeferred = createPhotoUploadDeferred()
      .always(function () {
        clearTimeout(fakeProgressTimer);
        iframeDeferred = null;
        $iframe.remove();
      });
    var $iframe = $('<iframe name=upload>').hide().appendTo('body').load(function () {
      deferred.resolve();
      var o;
      try {
        o = JSON.parse($iframe.contents().find('body').text());
      } catch (err) {
      }
      $photo.css('background-image', o ? 'url(' + o.url + ')' : '').removeClass('unset');
      $(form).removeAttr('method target action');
    });
    form.method = 'POST';
    form.target = 'upload';
    form.action = $photo.data('form-uri');
    form.submit();

    var fakeProgressTimer;
    fakeProgress(0, 100);
    function fakeProgress(frac, ms) {
      deferred.notify(frac);
      fakeProgressTimer = setTimeout(fakeProgress.bind(null, 1 - (1 - frac) * .9, ms * 1.1), ms);
    }
  }

  function createPhotoUploadDeferred() {
    return $.Deferred()
      // .progress(updateUploadProgressBar)
      // .notify(0)
      // .done(updateUploadProgressBar.bind(null, 1))
  }
  var uploadProgressBar = $('.form-photo-progress')[0];
  function updateUploadProgressBar(frac) {
    uploadProgressBar.style.left = Math.round(100 * frac) + '%';
  }

  var localPhotoUrl;
  function showLocalPhotoDialog(upload) {
    if (localPhotoUrl) {
      URL.revokeObjectURL(localPhotoUrl);
    }
    localPhotoUrl = URL.createObjectURL(upload.file);
    photoDialog
      .show(localPhotoUrl)
      .done(function (details) {
        var scale = $photo.innerWidth() / details.size;
        $photo.css({
          'background-image': 'url(' + localPhotoUrl + ')',
          'background-size': scale * details.width + 'px auto',
          'background-position': -(scale * details.x) + 'px ' + -(scale * details.y) + 'px'
        }).removeClass('unset')
        .removeData().data(details).data('uploadPromise', upload.promise);
      })
      .always(function () {
        $('.form-photo-file').val(null);
      });
  }

  var photoDialog = (function () {
    var $dialog, $mask, $image, $slider, deferred, hideTimer;
    var INNER_SIZE = 200;
    var SHADE_SIZE = 40;
    var OUTER_SIZE = INNER_SIZE + 2 * SHADE_SIZE;
    var SLIDER_MAX = 180;

    return {
      show: function (photoUrl) {
        var img = new Image();
        img.onload = onPhotoLoad;
        img.src = photoUrl;

        $dialog = ($dialog || $('.photo-dialog').remove().css('display', '')).click(onDialogClick);
        $mask = $('.photo-dialog-mask', $dialog).mousedown(onMaskMouseDown);
        $image = $(img).addClass('photo-dialog-img').insertBefore($mask);
        $slider = $('.photo-dialog-slider', $dialog);
        clearTimeout(hideTimer), hideTimer = null;

        return (deferred = $.Deferred());
      }
    };

    function onPhotoLoad() {
      var nw = this.width;
      var nh = this.height;
      var dMin = INNER_SIZE;
      var dMax = Math.max(OUTER_SIZE, Math.min(1000, nw, nh));
      var d0 = Math.max(INNER_SIZE, Math.min(OUTER_SIZE, nw, nh));
      var wScale = Math.max(1, nw / nh);
      var hScale = Math.max(1, nh / nw);
      var w = d0 * wScale;
      var h = d0 * hScale;
      var top = .5 * (OUTER_SIZE - h);
      var left = .5 * (OUTER_SIZE - w);
      $image
        .css({width: w, height: h, top: top, left: left})
        .data({
          naturalWidth: nw,
          naturalHeight: nh,
          width: w,
          height: h,
          top: top,
          left: left});
      $slider.slider({
        max: SLIDER_MAX,
        value: Math.round(SLIDER_MAX * (d0 - dMin) / (dMax - dMin)),
        slide: onSliderSlide.bind($image[0], $image.data(), percentToPx(dMin, dMax), wScale, hScale)});
      $dialog.appendTo('body').layout().addClass('dialog-showing').find('.ui-slider-handle').focus();
      onEsc(hide);
    }

    function hide() {
      offEsc(hide);
      $dialog.removeClass('dialog-showing');
      hideTimer = setTimeout(function () {
        $dialog.remove();
        $image.remove();
        $mask = $image = $slider = deferred = hideTimer = null;
      }, 500);
    }

    function percentToPx(pxMin, pxMax) {
      var factor = (pxMax - pxMin) / SLIDER_MAX;
      return function (pct) {
        return pxMin + pct * factor;
      };
    }

    function onSliderSlide(data, pctToPx, wScale, hScale, e, ui) {
      var d = pctToPx(ui.value);
      var w = d * wScale;
      var h = d * hScale;
      var top = Math.min(SHADE_SIZE, Math.max(SHADE_SIZE + INNER_SIZE - h, data.top - .5 * (h - data.height)));
      var left = Math.min(SHADE_SIZE, Math.max(SHADE_SIZE + INNER_SIZE - w, data.left - .5 * (w - data.width)));
      this.style.top = top + 'px';
      this.style.left = left + 'px';
      this.style.width = w + 'px';
      this.style.height = h + 'px';
      data.width = w;
      data.height = h;
      data.top = top;
      data.left = left;
    }

    function onMaskMouseDown(e) {
      e.preventDefault();
      var x0 = e.screenX;
      var y0 = e.screenY;
      var data = $image.data();
      var leftMin = INNER_SIZE + SHADE_SIZE - data.width;
      var topMin = INNER_SIZE + SHADE_SIZE - data.height;
      var move = throttle(onMaskMouseMove.bind($image[0], x0, y0, data.left, data.top, leftMin, topMin, data), 10);
      document.addEventListener('mousemove', move, true);
      document.addEventListener('mouseup', onMaskMouseUp, true);
      document.addEventListener('mouseout', onMaskMouseOut, true);
      $mask.data('move', move);
      $dialog.addClass('dragging');
    }

    function onMaskMouseMove(x0, y0, left0, top0, leftMin, topMin, data, e) {
      var left = data.left = Math.min(SHADE_SIZE, Math.max(leftMin, left0 + e.screenX - x0));
      var top = data.top = Math.min(SHADE_SIZE, Math.max(topMin, top0 + e.screenY - y0));
      this.style.left = left + 'px';
      this.style.top = top + 'px';
    }

    function onMaskMouseUp() {
      document.removeEventListener('mousemove', $mask.data('move'), true);
      document.removeEventListener('mouseup', onMaskMouseUp, true);
      document.removeEventListener('mouseout', onMaskMouseOut, true);
      $mask.removeData('move');
      $dialog.removeClass('dragging');
    }

    function onMaskMouseOut(e) {
      if (!e.relatedTarget) {
        onMaskMouseUp();
      }
    }

    function onDialogClick(e) {
      if (e.which !== 1) return;
      var $el = $(e.target);
      var submitButton = $el.hasClass('photo-dialog-submit');
      if (submitButton || $el.is('.photo-dialog-cancel,.photo-dialog-x')) {
        var o = $image.data();
        if (submitButton) {
          var scale = o.naturalWidth / o.width;
          deferred.resolve({
            width: o.naturalWidth,
            height: o.naturalHeight,
            x: Math.round(scale * (SHADE_SIZE - o.left)),
            y: Math.round(scale * (SHADE_SIZE - o.top)),
            size: Math.round(scale * INNER_SIZE)});
        } else {
          deferred.reject();
        }
        hide();
      }
    }
  }());

  var resetPasswordDialog = (function () {
    var $dialog, $form, promise, hideTimer;
    return {
      show: function (emailAddr) {
        $dialog = $dialog || $('.reset-password').remove().css('display', '');
        $dialog.removeClass('reset-password-sent').click(onDialogClick);
        $form = $dialog.find('.reset-password-form').submit(onFormSubmit);
        clearTimeout(hideTimer), hideTimer = null;

        $dialog.appendTo('body').layout().addClass('dialog-showing');
        setTimeout(function () {
          $dialog.find('.reset-password-email').val(emailAddr).focus().select();
        }, 10);
        onEsc(hide);
      }
    };

    function hide() {
      offEsc(hide);
      $dialog.removeClass('dialog-showing');
      hideTimer = setTimeout(function () {
        $dialog.remove();
        $form = promise = null;
      }, 500);
    }

    function onFormSubmit(e) {
      e.preventDefault();
      if (promise && promise.status() === 'pending') {
        return false;
      }
      var $email = $form.find('.reset-password-email');
      var email = kifi.form.validateEmailAddress($email);
      if (email) {
        Tracker.trackClick($form.find('button')[0]);
        promise = $.postJson(this.action, {email: email})
        .done(function (data) {
          $dialog.find('.reset-password-addresses').append($.map(data.addresses, function (addr) {
            return $('<li class=reset-password-address>').text(addr);
          }));
          $dialog.addClass('reset-password-sent');
          setTimeout($.fn.focus.bind($dialog.find('.reset-password-cancel')), 100);
        })
        .fail(function (xhr) {
          var o = xhr.responseJSON;
          if (o && o.error === 'no_account') {
            Tracker.track('visitor_viewed_page', { error: 'noAccount', errorValue: email });
            kifi.form.showError($email, 'Sorry, we don’t recognize this email address.');
          }
        })
        .always(function () {
          promise = null;
        });
      }
    }

    function onDialogClick(e) {
      if (e.which === 1) {
        var $el = $(e.target);
        if ($el.hasClass('reset-password-submit')) {
          $form.submit();
        } else if ($el.is('.reset-password-cancel,.dialog-x,.dialog-cell')) {
          hide();
        }
      }
    }
  }());


  var claimAccountDialog = (function () {
    var $dialog, $form, hideTimer;
    return {
      show: function (emailAddr) {
        $dialog = $dialog || $('.claim-account').remove().css('display', '');
        $dialog.click(onDialogClick);
        $form = $dialog.find('form').submit(onLoginFormSubmit).reset();
        clearTimeout(hideTimer), hideTimer = null;

        $dialog.appendTo('body').layout().addClass('dialog-showing');
        $dialog.find('.form-email-addr').val(emailAddr);
        setTimeout(function () {
          $dialog.find('.form-password').focus().select();
        }, 10);
        onEsc(hide);
      }
    };

    function hide() {
      offEsc(hide);
      $dialog.removeClass('dialog-showing');
      hideTimer = setTimeout(function () {
        $dialog.remove();
        $form = null;
      }, 500);
    }

    function onDialogClick(e) {
      if (e.which === 1) {
        var $el = $(e.target);
        if ($el.hasClass('claim-account-submit')) {
          $form.submit();
        } else if ($el.is('.claim-account-cancel,.dialog-x,.dialog-cell')) {
          hide();
        }
      }
    }
  }());

  function onEsc(handler) {
    onKeyDown.guid = handler.guid = handler.guid || $.guid++;
    $(document).keydown(onKeyDown);
    function onKeyDown(e) {
      if (e.which === 27) {
        handler(e);
        e.preventDefault();
      }
    }
  }

  function offEsc(handler) {
    $(document).off('keydown', handler);
  }

  // from underscore.js 1.5.2 underscorejs.org
  function throttle(func, wait, options) {
    var context, args, result;
    var timeout = null;
    var previous = 0;
    options || (options = {});
    var later = function () {
      previous = options.leading === false ? 0 : Date.now();
      timeout = null;
      result = func.apply(context, args);
    };
    return function () {
      var now = Date.now();
      if (!previous && options.leading === false) previous = now;
      var remaining = wait - (now - previous);
      context = this;
      args = arguments;
      if (remaining <= 0) {
        clearTimeout(timeout);
        timeout = null;
        previous = now;
        result = func.apply(context, args);
      } else if (!timeout && options.trailing !== false) {
        timeout = setTimeout(later, remaining);
      }
      return result;
    };
  };
}());

function fbAsyncInit() {
  FB.Event.subscribe('auth.authResponseChange', function (o) {
    var id = o && o.authResponse && o.authResponse.userID;
    if (id) {
      var url = 'https://graph.facebook.com/' + id + '/picture?return_ssl_resources=1&width=50&height=50'
      $('.form-network.facebook>.form-network-icon').html('<img src="' + url + '" class="form-network-pic">');
    }
  });
  FB.init({appId: $('#facebook-jssdk').data('appId')});
}

function onLoadLinkedInApi() {
  IN.User.isAuthorized() && IN.API.Profile('me').fields('picture-url;secure=true').result(function (o) {
    var url = o && o.values && o.values[0] && o.values[0].pictureUrl;
    if (url) {
      $('.form-network.linkedin>.form-network-icon').html('<img src="' + url + '" class="form-network-pic">');
    }
  });
}
