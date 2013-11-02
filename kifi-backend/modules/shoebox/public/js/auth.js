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
    this.clientHeight;
  }
}());

var kifi = {};
kifi.form = (function () {
  'use strict';
  var emailAddrRe = /^[a-zA-Z0-9.!#$%&'*+\/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$/;
  return {
    showError: function ($in, msg, opts) {
      var $err = $('<div class=form-error>').css('visibility', 'hidden').html(msg).appendTo('body')
        .position({my: 'left top', at: 'left bottom+10', of: $in, collision: 'fit none'})
        .css('visibility', '')
        .delay(opts && opts.ms || 1000).fadeOut(300, removeError);
      $in.blur();  // closes browser autocomplete suggestion list
      $in.focus().select().on('input blur', removeError);
      function removeError() {
        $err.remove();
        $in.off('input blur', removeError);
      }
    },
    validateEmailAddress: function ($in) {
      var s = $.trim($in.val());
      if (!s) {
        kifi.form.showError($in, 'Please enter your email address');
      } else if (!emailAddrRe.test(s)) {
        kifi.form.showError($in, 'Invalid email address');
      } else {
        return s;
      }
    },
    validateNewPassword: function ($in) {
      var s = $in.val();
      if (!s) {
        kifi.form.showError($in, 'Please choose a password<br>for your account', {ms: 1500});
      } else if (s.length < 7) {
        kifi.form.showError($in, 'Password must be at least 7 characters', {ms: 1500});
      } else {
        return s;
      }
    },
    validatePassword: function ($in) {
      var s = $in.val();
      if (!s) {
        kifi.form.showError($in, 'Please enter your password');
      } else if (s.length < 7) {
        kifi.form.showError($in, 'Incorrect password', {ms: 1500});
      } else {
        return s;
      }
    },
    validateName: function ($in) {
      var s = $.trim($in.val());
      if (!s) {
        kifi.form.showError($in,
          '<div class=form-error-title>Name is required</div>' +
          '<div class=form-error-explanation>We need your name so that<br>your friends will be able to<br>communicate with you</div>',
          {ms: 3000});
      } else {
        return s;
      }
    }
  };
}());

(function () {
  'use strict';
  var baseUri = '';
  var $logoL = $('.curtain-logo-l');
  var $logoR = $('.curtain-logo-r');

  $('.curtain-action').on('mousedown click', function (e) {
    if (e.which !== 1 || $('body').hasClass('curtains-drawn')) return;
    var isLogin = $(this).hasClass('curtain-login');
    var $signup = $('.signup').css('display', isLogin ? 'none' : 'block');
    var $login = $('.login').css('display', !isLogin ? 'none' : 'block');
    var $form = isLogin ? $login : $('.signup-1');
    $('.page-title').text($form.data('title'));
    $('body').addClass('curtains-drawn');
    setTimeout(function () {
      $form.find('.form-email-addr').focus();
    });
  });
  $('.curtain-back').on('mousedown click', function (e) {
    if (e.which !== 1) return;
    $('body').removeClass('curtains-drawn');
  });

  $('.form-network').click(function (e) {
    if (e.which !== 1) return;
    var $a = $(this);
    var network = ['facebook', 'linkedin'].filter($.fn.hasClass.bind($a))[0];
    var $form = $a.closest('form');
    if ($form.hasClass('signup-1')) {
      window.location = baseUri + '/signup/' + network;
    } else if ($form.hasClass('login')) {
      window.location = baseUri + '/login/' + network;
    }
  });

  var signup1Promise;
  $('.signup-1').submit(function (e) {
    if (signup1Promise && signup1Promise.state() === 'pending') {
      return false;
    }
    $('.form-error').remove();
    var $form = $(this);
    var email = kifi.form.validateEmailAddress($form.find('.form-email-addr'));
    var password = email && kifi.form.validateNewPassword($form.find('.form-password'));
    if (email && password) {
      signup1Promise = $.postJson(baseUri + '/auth/sign-up', {
        email: email,
        password: password
      }).done(function (data) {
        if (!data.finalized) {
          transitionTitle($('.signup-2').data('title'));
          $('body').addClass('finalizing droppable');
          setTimeout(function () {
            $('.form-first-name').focus();
          }, 200);
        } else {
          navigateToApp();
        }
      }).fail(function (xhr) {
        signup1Promise = null;
      });
    }
    return false;
  });
  function transitionTitle(text) {
    $('.page-title.obsolete').remove();
    var $title = $('.page-title');
    $title.after($title.clone().text(text)).addClass('obsolete').layout();
  }
  function navigateToApp() {
    window.location = '/';
  }

  var signup2Promise;
  $('.signup-2-email').submit(function (e) {
    if (signup2Promise && signup2Promise.state() === 'pending') {
      return false;
    }
    $('.form-error').remove();
    var $form = $(this);
    var first = kifi.form.validateName($form.find('.form-first-name'));
    var last = first && kifi.form.validateName($form.find('.form-last-name'));
    if (first && last) {
      var pic = $photo.data();
      signup2Promise = $.when(pic.uploadPromise).done(function (upload) {
         signup2Promise = $.postJson(baseUri + '/auth/email-finalize', {
          firstName: first,
          lastName: last,
          picToken: upload && upload.token,
          picWidth: pic.width,
          picHeight: pic.height,
          cropX: pic.x,
          cropY: pic.y,
          cropSize: pic.size
        })
        .done(navigateToApp)
        .fail(function (xhr) {
          signup2Promise = null;
        });
      });
    }
    return false;
  });
  $('.signup-2-social').submit(function (e) {
    if (signup2Promise && signup2Promise.state() === 'pending') {
      return false;
    }
    $('.form-error').remove();
    var $form = $(this);
    var email = kifi.form.validateEmailAddress($form.find('.social-email'));
    var password = kifi.form.validateNewPassword($form.find('.form-password'));
    if (password) {
      signup2Promise = $.postJson(baseUri + '/auth/social-finalize', {
        firstName: $form.data('first'),
        lastName: $form.data('last'),
        email: email,
        password: password
      })
      .done(navigateToApp)
      .fail(function (xhr) {
        signup2Promise = null;
      });
    }
    return false;
  }).on('click', '.social-change-email', function (e) {
    if (e.which !== 1) return;
    $('.social-email').removeAttr('disabled').focus().select();
    $(this).addClass('clicked');
  });

  var loginPromise;
  $('.login').submit(function (e) {
    if (loginPromise && loginPromise === 'pending') {
      return false;
    }
    $('.form-error').remove();
    var $form = $(this);
    var $email = $form.find('.form-email-addr');
    var $password = $form.find('.form-password');
    var email = kifi.form.validateEmailAddress($email);
    var password = email && kifi.form.validatePassword($password);
    if (email && password) {
      loginPromise = $.postJson(baseUri + '/auth/log-in', {
        username: email,
        password: password
      })
      .done(navigateToApp)
      .fail(function (xhr) {
        loginPromise = null;
        if (xhr.status === 403) {
          var o = xhr.responseJson;
          if (o && o.error === 'no_such_user') {
            kifi.form.showError($email, 'There is no account associated<br>with this email address', {ms: 2000});
          } else {
            kifi.form.showError($password, 'Incorrect password');
          }
        } else {
          // TODO: offline? 500?
        }
      });
    }
    return false;
  }).on('click', '.password-forgot', function (e) {
    if (e.which !== 1) return;
    resetPasswordDialog.show($(this).closest('form').find('.form-email-addr').val());
  });

  var URL = window.URL || window.webkitURL;
  var $photo = $('.form-photo');
  $('.form-photo-a').click(function (e) {
    if (e.which !== 1) return;
    var $a = $(this);
    if ($a.hasClass('facebook')) {
      window.open('https://www.facebook.com', 'photo', 'width=720,height=400,dialog=yes,menubar=no,resizable=yes,scrollbars=yes,status=yes');
    } else if ($a.hasClass('linkedin')) {
      window.open('https://www.linkedin.com', 'photo', 'width=720,height=400,dialog=yes,menubar=no,resizable=yes,scrollbars=yes,status=yes');
    }
  });
  $('.form-photo-file').change(function () {
    if (this.files && URL) {
      var upload = uploadPhotoXhr2(this.files);
      if (upload) {
        showLocalPhotoDialog(upload)
      }
    } else {
      uploadPhotoIframe(this.form);
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
      xhr.open('POST', baseUri + '/auth/upload-binary-image', true);
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
    form.action = baseUri + '/auth/upload-multipart-image';
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
    var $dialog, $mask, $image, $slider, deferred;
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
      $dialog.appendTo('body');
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
      var submitted = $el.hasClass('photo-dialog-submit');
      if (submitted || $el.is('.photo-dialog-cancel,.photo-dialog-x,.dialog-cell')) {
        var o = $image.data();
        $dialog.remove();
        $image.remove();
        if (submitted) {
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
        $mask = $image = $slider = deferred = null;
      }
    }
  }());

  var resetPasswordDialog = (function () {
    var $dialog, $form, promise;
    return {
      show: function (emailAddr) {
        $dialog = $dialog || $('.reset-password').remove().css('display', '');
        $form = $dialog.find('.reset-password-form').submit(onFormSubmit);

        $dialog.appendTo('body').click(onDialogClick);
        $dialog.find('.reset-password-email').val(emailAddr).focus().select();
      }
    };

    function onFormSubmit(e) {
      e.preventDefault();
      if (promise && promise.status() === 'pending') {
        return false;
      }
      var $email = $form.find('.reset-password-email');
      var email = kifi.form.validateEmailAddress($email);
      if (email) {
        promise = $.postJson(this.action, {email: email})
        .done(function (resp) {
          if (resp.error === 'no_account') {
            kifi.form.showError($email, 'Sorry, we don’t recognize this email address.', {ms: 2000});
          } else {
            $dialog.addClass('reset-password-sent');
          }
        })
        .always(function () {
          promise = null;
        });
      }
    }

    function onDialogClick(e) {
      if (e.which !== 1) return;
      if (e.target.className === 'reset-password-submit') {
        $form.submit();
      } else if ($(e.target).is('.reset-password-cancel,.reset-password-x,.dialog-cell')) {
        hideDialog();
      }
    }

    function hideDialog() {
      $dialog.remove().removeClass('reset-password-sent');
      $form = null;
      promise = null;
    }
  }());

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
