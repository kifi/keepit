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
    $form.find('.form-email-addr').focus();
    $('body').addClass('curtains-drawn');
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

  var emailAddrRe = /^[a-zA-Z0-9.!#$%&'*+\/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$/;
  function showFormError($in, msg, opts) {
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
  }
  function validateEmailAddress($in) {
    var s = $.trim($in.val());
    if (!s) {
      showFormError($in, 'Please enter your email address');
    } else if (!emailAddrRe.test(s)) {
      showFormError($in, 'Invalid email address');
    } else {
      return s;
    }
  }
  function validateNewPassword($in) {
    var s = $in.val();
    if (!s) {
      showFormError($in, 'Please choose a password<br>for your account', {ms: 1500});
    } else if (s.length < 7) {
      showFormError($in, 'Password must be at least 7 characters', {ms: 1500});
    } else {
      return s;
    }
  }
  function validatePassword($in) {
    var s = $in.val();
    if (!s) {
      showFormError($in, 'Please enter your password');
    } else if (s.length < 7) {
      showFormError($in, 'Incorrect password', {ms: 1500});
    } else {
      return s;
    }
  }
  function validateName($in) {
    var s = $.trim($in.val());
    if (!s) {
      showFormError($in,
        '<div class=form-error-title>Name is required</div>' +
        '<div class=form-error-explanation>We need your name so that<br>your friends will be able to<br>communicate with you</div>',
        {ms: 3000});
    } else {
      return s;
    }
  }

  var signup1Promise;
  $('.signup-1').submit(function (e) {
    if (signup1Promise && signup1Promise.state() === 'pending') {
      return false;
    }
    $('.form-error').remove();
    var $form = $(this);
    var email = validateEmailAddress($form.find('.form-email-addr'));
    var password = email && validateNewPassword($form.find('.form-password'));
    if (email && password) {
      signup1Promise = $.postJson(baseUri + '/auth/sign-up', {
        email: email,
        password: password
      }).done(function(data) {
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
    var first = validateName($form.find('.form-first-name'));
    var last = first && validateName($form.find('.form-last-name'));
    if (first && last) {
      signup2Promise = $.postJson(baseUri + '/auth/email-finalize', {
        firstName: first,
        lastName: last
        // picToken: TODO
      }).fail(function (xhr) {
        signup2Promise = null;
      });
      $.when(signup2Promise, photoPromise).done(navigateToApp);
    }
    return false;
  });
  $('.signup-2-social').submit(function (e) {
    if (signup2Promise && signup2Promise.state() === 'pending') {
      return false;
    }
    $('.form-error').remove();
    var $form = $(this);
    var email = validateEmailAddress($form.find('.form-email-addr'));
    var password = email && validateNewPassword($form.find('.form-password'));
    var first = email && password && validateName($form.find('.form-first-name'));
    var last = email && password && first && validateName($form.find('.form-last-name'));
    if (email && password && first && last) {
      signup2Promise = $.postJson(baseUri + '/auth/social-finalize', {
        email: email,
        password: password,
        firstName: first,
        lastName: last
        // picToken: TODO
      }).fail(function (xhr) {
        signup2Promise = null;
      });
      $.when(signup2Promise, photoPromise).done(navigateToApp);
    }
    return false;
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
    var email = validateEmailAddress($email);
    var password = email && validatePassword($password);
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
            showFormError($email, 'There is no account associated<br>with this email address', {ms: 2000});
          } else {
            showFormError($password, 'Incorrect password');
          }
        } else {
          // TODO: offline? 500?
        }
      });
    }
    return false;
  });

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
  $('.form-photo-file').change(function (e) {
    if (this.files) {
      uploadPhotoXhr2(this.files);
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
        uploadPhotoXhr2(e.originalEvent.dataTransfer.files);
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
  var URL = window.URL || window.webkitURL;
  function uploadPhotoXhr2(files) {
    var file = Array.prototype.filter.call(files, isImage)[0];
    if (!file) return;

    if (URL) {
      var url = $photo.data('url');
      if (url) URL.revokeObjectURL(url);
      url = URL.createObjectURL(file);
      $photo.css('background-image', 'url(' + url + ')').data('url', url);
    } else {  // TODO: URL alternative for Safari 5
      $photo.css('background-image', '');
    }

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
    xhr.upload.addEventListener('load', function() {
      deferred.resolve();
    });
    xhr.upload.addEventListener('loadend', function() {
      if (photoXhr2 === xhr) {
        photoXhr2 = null;
      }
      if (deferred.state() === 'pending') {
        deferred.reject();
      }
    });
    xhr.open('POST', 'https://www.kifi.com/testing/upload', true);
    xhr.send(file);
  }
  function isImage(file) {
    return file.type.search(/^image\/(?:jpeg|png|gif)$/) === 0;
  }

  var iframeDeferred;
  function uploadPhotoIframe(form) {
    $photo.css('background-image', 'none');

    if (iframeDeferred) {
      iframeDeferred.reject();  // clean up previous in-progress upload
    }
    var deferred = iframeDeferred = createPhotoUploadDeferred()
      .always(function() {
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
      $photo.css('background-image', o ? 'url(' + o.url + ')' : '');
      $(form).removeAttr('method target action');
    });
    form.method = 'POST';
    form.target = 'upload';
    form.action = 'https://www.kifi.com/testing/upload';
    form.submit();

    var fakeProgressTimer;
    fakeProgress(0, 100);
    function fakeProgress(frac, ms) {
      deferred.notify(frac);
      fakeProgressTimer = setTimeout(fakeProgress.bind(null, 1 - (1 - frac) * .9, ms * 1.1), ms);
    }
  }

  var photoPromise;
  function createPhotoUploadDeferred() {
    var deferred = $.Deferred();
    photoPromise = deferred.promise();
    return deferred
      .progress(updateUploadProgressBar)
      .notify(0)
      .done(updateUploadProgressBar.bind(null, 1))
      .always(function() {
        photoPromise = null;
      });
  }
  var uploadProgressBar = $('.form-photo-progress')[0];
  function updateUploadProgressBar(frac) {
    uploadProgressBar.style.left = Math.round(100 * frac) + '%';
  }
}());
