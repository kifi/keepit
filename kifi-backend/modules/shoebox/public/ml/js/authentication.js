$(function() {

  //
  // Listeners
  //

  $('.form-input').on('click keypress', hideError);

  /* General Modal Functionalities */
  $('.modal-overlay').on('click', function() {
    if (event.target.className === 'modal-overlay' || event.target.className === 'modal-cell') {
      hideModal();
    }
  });

  /* Forgot password */
  $('.forgot-password-link').click(showForgotPasswordModal);
  $('.forgot-password-modal .modal-x').click(resetForgotPasswordModal);
  $('.forgot-password-modal .fp-form .modal-button-cancel').click(resetForgotPasswordModal);
  $('.forgot-password-modal .modal-button-close').click(resetForgotPasswordModal);
  $('.forgot-password-modal .fp-form').submit(submitForgotPassword);


  var kifi = {};

  // Log in with email/password
  kifi.loginWithEmail = function (e) {
    e.preventDefault();
    var $form = $(this);
    var $email = $form.find('.login-email');
    var $password = $form.find('.login-password');
    var trackingType = window.location.pathname.search('linkSocial') >= 0 ? 'linkSocialAccount' : 'login';

    var validEmail = validateEmailAddress($email, $form.find('#error-login-email'), trackingType);
    if (!validEmail) {
      return;
    }
    var validPassword = validatePassword($password, $form.find('#error-login-password'), trackingType);
    if (!validPassword) {
      return;
    }
    var animation = animateButton($('.btn-authentication'));

    $.postJson(this.action, {
      'username': validEmail,
      'password': validPassword
    }).done(function (data) {
      if (data.uri) {
        window.location = data.uri;
        animation.update(1);
        animation.success();
      } else {
        console.log(data);
      }
    }).fail(function (xhr) {
      animation.fail();
      // possible errors: no_such_user, wrong_password, bad_form (bad email, bad password, etc.)
      var body = xhr.responseJSON || {};
      var $email = $('.form-input.signup-email');
      var $password = $('.form-input.signup-password');
      if (body.error === 'no_such_user') {
        errorUserNotFound($('#error-login-email'), $email, trackingType);
      } else if (body.error === 'wrong_password') {
        errorWrongPassword($('#error-login-password'), $password, trackingType);
      } else {
        errorUnknown($('#login-email'), $email, trackingType);
      }
    });
    return false;
  };
  $('.login-email-pass').submit(kifi.loginWithEmail);

  // Sign up with email/password (sign up 1)
  kifi.signupWithEmailPassword = function (e) {
    e.preventDefault();
    var $form = $(this);
    var $email = $form.find('.signup-email');
    var $password = $form.find('.signup-password');

    var validEmail = validateEmailAddress($email, $form.find('#login-email'), 'signup');
    if (!validEmail) {
      return;
    }
    var validPassword = validatePassword($password, $form.find('#login-password'), 'signup');
    if (!validPassword) {
      return;
    }

    var animation = animateButton($('.btn-authentication'));
    $.postJson(this.action, {
      'email': validEmail,
      'password': validPassword
    }).done(function (data) {
      if (data.success) { // successes return: {success: true}
        window.location = '/new/signupName'; // todo: change to /signup
        animation.update(1);
        animation.success();
      } else {
        console.log(data);
      }
    }).fail(function (xhr) {
      // possible errors: error.email, password_too_short, user_exists_failed_auth
      animation.fail();
      var body = xhr.responseJSON || {};
      var $email = $('.form-input.signup-email');
      var $password = $('.form-input.signup-password');
      if (body.error === "error.email") {
        errorInvalidEmail($('#login-email'), $email, 'signup');
      } else if (body.error === "password_too_short") {
        errorShortPassword($('#login-password'), $password, 'signup');
      } else if (body.error === "user_exists_failed_auth") { // account already exists but incorrect password
        errorUserExists($('#login-email'), $email, 'signup');
      } else {
        errorUnknown($('#login-email'), $email, 'signup');
      }
    });
    return false;
  };
  $('.signup-email-pass').submit(kifi.signupWithEmailPassword);

  // Sign up with email/password (sign up 2), needs name
  var photoUpload; // gets set if there's an image upload in progress
  kifi.signupGetName = function (e) {
    e.preventDefault();
    var $form = $(this);
    var $firstName = $form.find('.first-name');
    var $lastName = $form.find('.last-name');

    var validFirstName = validateName($firstName, $form.find('#signup-firstname'), 'first', 'signup2Email');
    if (!validFirstName) {
      return;
    }
    var validLastName = validateName($lastName, $form.find('#signup-lastname'), 'last', 'signup2Email');
    if (!validLastName) {
      return;
    }
    var form = this;
    var animation = animateButton($('.btn-authentication'));
    $.when(photoUpload && photoUpload.promise).always(function (upload) {
      $.postJson(form.action, {
        firstName: validFirstName,
        lastName: validLastName,
        picToken: upload && upload.token // upload && upload.token
      }).done(function (data) {
        if (data.uri) { // successes return: {success: true}
          window.location = data.uri;
          animation.update(1);
          animation.success();
        } else {
          console.log(data);
        }
      }).fail(function (xhr) {
        animation.fail();
        var $form = $('.form-input.first-name');
        errorUnknown($('#signup-firstname'), $form, 'signup2Email');
      });
    });

    return false;
  };
  $('.signup-name').submit(kifi.signupGetName);
  // Image utilities

  var $userPhoto = $('.image-upload');
  $userPhoto.click(function (e) {
    if (e.which === 1) {
      $('.form-photo-file').click();
    }
  });
  var $photoFile = $('.form-photo-file');
  var URL = window.URL || window.webkitURL;
  var localPhotoUrl;
  $('.form-photo-file').change(function () {
    if (this.files && URL) {
      photoUpload = uploadPhotoXhr2(this.files);
      if (photoUpload) {

        photoUpload.promise.fail(function() {
          var $errorField = $('#error-invalid-image');
          var $inputField = $('.upload-image-btn');
          errorImageFile($errorField, $inputField);
        });

        // wait for file dialog to go away before starting dialog transition
        setTimeout(function () {
          if (localPhotoUrl) {
            URL.revokeObjectURL(localPhotoUrl);
          }
          localPhotoUrl = URL.createObjectURL(photoUpload.file);
          var img = new Image();
          img.onload = function (e) {
            console.log('onload', e); // todo, needed?
          };
          img.src = localPhotoUrl;
          $userPhoto.css({
            'background-image': 'url(' + localPhotoUrl + ')'
          }).find('.add').hide();
        }, 200);
      }
    } else {
      // uploadPhotoIframe(this.form); // todo???
    }
  });
  var photoXhr2;
  function uploadPhotoXhr2(files) {
    hideError();
    var file = Array.prototype.filter.call(files, isImage)[0];
    if (file) {
      if (photoXhr2) {
        photoXhr2.abort();
      }
      var xhr = photoXhr2 = new XMLHttpRequest();
      var deferred = $.Deferred();
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
      xhr.open('POST', $photoFile.data('uri'), true);
      xhr.send(file);
      return {file: file, promise: deferred.promise()};
    }
  }
  function isImage(file) {
    return file.type.search(/^image\/(?:jpeg|png|gif)$/) === 0;
  }

  // Sign up social account (signup 2), needs email
  kifi.signupGetEmail = function (e) {
    e.preventDefault();
    var $form = $(this);
    var $email = $form.find('.email');

    var validEmail = validateEmailAddress($email, $form.find('#error-signup-email'), 'signup2Social');
    if (!validEmail) {
      return;
    }
    var $first = $form.find('.form-first-name');
    var $last = $form.find('.form-last-name');
    var animation = animateButton($('.btn-authentication'));

    $.postJson(this.action, {
      email: validEmail,
      firstName: $first.val() || '',
      lastName: $last.val() || ''
    }).done(function (data) {
      if (data.uri) { // successes return: {success: true}
        window.location = data.uri;
        animation.update(1);
        animation.success();
      } else {
        window.location = '/'; // todo: best location for success?
      }
    }).fail(function (xhr) {
      animation.fail();
      var body = xhr.responseJSON || {};
      var $form = $('.form-input.email');
      if (body.error === 'error.email') {
        errorInvalidEmail($('#error-signup-email'), $form, 'signup2Social');
      } else if (body.error === 'error.required') {
        errorUnrecognizedEmail($('#error-signup-email'), $form, 'signup2Social');
      } else {
        errorUnknown($('#error-signup-email'), $form, 'signup2Social');
      }
    });
    return false;
  };
  $('.signup-email').submit(kifi.signupGetEmail);


  //
  // Modal Functions
  //
  function hideModal() {
    event.preventDefault();
    $('.modal-overlay').removeClass('show');
  }

  function showForgotPasswordModal() {
    hideError();
    $('.modal-overlay.forgot-password').addClass('show');
    var modal = $('.forgot-password-modal');
    modal.find('.fp-form').show();
    modal.find('.fp-success').hide();
    setTimeout(function () {
      modal.find('.fp-input').focus();
    }, 100);
    modal.find('.fp-input').val($('.login-email').val());
  }

  function submitForgotPassword(event) {
    event.preventDefault();
    var trackingType = window.location.pathname.search('linkSocial') >= 0 ? 'linkSocialAccount' : 'forgotPassword';

    // validate email
    var $email = $('.fp-input');
    var validEmail = validateEmailAddress($email, $('#error-fp'), trackingType);

    if (!validEmail) {
      return;
    }
    // make request
    var actionUri = $('.fp-form')[0].action;
    $.postJson(actionUri, {email: validEmail})
    .done(function () {
      // show success pane, hide original pane
      var modal = $('.forgot-password-modal');
      modal.find('.fp-address').html(validEmail);
      modal.find('.fp-form').hide();
      modal.find('.fp-success').show();
    })
    .fail(function (xhr) { // errors: no_account
      var body = xhr.responseJSON || {};
      if (body.error === 'no_account') {
        errorUnrecognizedEmail($('#error-fp'), $('.fp-input'), trackingType);
      } else {
        errorUnknown($('#error-fp'), $('.fp-input'), trackingType);
      }
    });
    return false;
  }

  function resetForgotPasswordModal() {
    hideError();
    hideModal();
    var modal = $('.forgot-password-modal');
    setTimeout(function () {
      modal.find('.fp-success').hide();
      modal.find('.fp-form').show();
    }, 400);
  }


  //
  // Utilities
  //
  function error(errorField, errorHtml, $inputField) {
    $('#center_container').addClass('shake');
    errorField.html(errorHtml).fadeIn();
    setTimeout(function(){ $('#center_container').removeClass('shake'); }, 2000);
    if ($inputField) {
      $inputField.focus().select();
    }
  }

  function hideError() {
    $('.error').fadeOut();
  }

  // All Errors
  function errorEmptyPassword($errorField, $inputField, type) {
    if (type === 'signup') {
      Tracker.track('visitor_viewed_page', { type: type, error: 'noNewPassword' });
    } else {
      Tracker.track('visitor_viewed_page', { type: type, error: 'noPassword' });
    }
    error($errorField, 'Please enter your password', $inputField);
  }
  function errorShortPassword($errorField, $inputField, type) {
    if (type === 'signup') {
      Tracker.track('visitor_viewed_page', { type: type, error: 'shortNewPassword' });
    } else {
      Tracker.track('visitor_viewed_page', { type: type, error: 'shortPassword' });
    }
    error($errorField, 'Password is too short', $inputField);
  }
  function errorWrongPassword($errorField, $inputField, type) {
    Tracker.track('visitor_viewed_page', { type: type, error: 'wrongPassword' });
    error($errorField, 'Wrong password. Forgot it?<br><a href="#" class="errorTipResetPassword">Reset it here</a>.', $inputField);

    function showResetPasswordModal() {
      $('.error').fadeOut(); // hide error
      $('.modal-overlay.forgot-password').addClass('show'); // show forgot password modal
      var modal = $('.forgot-password-modal');
      modal.find('.fp-form').show();
      modal.find('.fp-success').hide();
    }
    $('.errorTipResetPassword').click(showResetPasswordModal);
  }
  function errorEmptyName($errorField, whichName, $inputField) {
    if (whichName === 'first') {
      Tracker.track('visitor_viewed_page', { type: 'signup2Email', error: 'noFirstName' });
    } else if (whichName === 'last') {
      Tracker.track('visitor_viewed_page', { type: 'signup2Email', error: 'noLastName' });
    }
    error($errorField, 'Your '+ whichName +' name is required', $inputField);
  }
  function errorEmptyEmail($errorField, $inputField, type) {
    Tracker.track('visitor_viewed_page', { type: type, error: 'noEmail' });
    error($errorField, 'Please enter your email address', $inputField);
  }
  function errorInvalidEmail($errorField, $inputField, type) {
    Tracker.track('visitor_viewed_page', { type: type, error: 'invalidEmail' });
    error($errorField, 'Invalid email address', $inputField);
  }
  function errorUserNotFound($errorField, $inputField, type) {
    Tracker.track('visitor_viewed_page', { type: type, error: 'unrecognizedEmail' });
    error($errorField, 'Whoops, we can’t find you.<br>Try a different email or social account?', $inputField);
  }
  function errorUnrecognizedEmail($errorField, $inputField, type) {
    Tracker.track('visitor_viewed_page', { type: type, error: 'unrecognizedEmail' });
    error($errorField, 'Sorry, we don’t recognize this email address.', $inputField);
  }
  function errorUserExists($errorField, $inputField, type) {
    Tracker.track('visitor_viewed_page', { type: type, error: 'wrongPassword' });
    error($errorField, 'An account already exists for this email!<br>Try <a href="/login">Logging In</a>', $inputField);
  }
  function errorUnknown($errorField, $inputField, type) {
    if (type === 'login' || type === 'linkSocialAccount') {
      Tracker.track('visitor_viewed_page', { type: 'login', error: 'unknownLoginError' });
    } else if (type === 'signup' || type === 'signup2Email' || type === 'signup2Social') {
      Tracker.track('visitor_viewed_page', { type: 'signup', error: 'unknownSignupError'})
    }
    error($errorField, 'Unknown Error:<br>Please contact us on <a href="http://support.kifi.com/hc/en-us/requests/new">Support</a>', $inputField);
  }
  function errorImageFile($errorField, $inputField) {
    Tracker.track('visitor_viewed_page', { type: 'signup2Email', error: 'invalidImageFile' });
    error($errorField, 'Image upload failed.<br>Please use a different image', $inputField);
  }


  //
  // Validation functions
  //
  var emailAddrRe = /^[a-zA-Z0-9.!#$%&'*+\/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+$/;
  function validateEmailAddress($email, $errorObj, type) { // pass email object & where error object should appear
    var s = $email.val();
    if (!s) {
      errorEmptyEmail($errorObj, $email, type);
    } else if (!emailAddrRe.test(s)) {
      errorInvalidEmail($errorObj, $email, type);
    } else {
      return s;
    }
  }

  function validatePassword($password, $errorObj, type) {
    var s = $password.val();
    if (!s) {
      errorEmptyPassword($errorObj, $password, type);
    } else if (s.length < 7) {
      errorShortPassword($errorObj, $password, type);
    } else {
      return s;
    }
  }

  function validateName($name, $errorObj, whichName, type) {
    var s = $.trim($name.val());
    if (!s) {
      errorEmptyName($errorObj, whichName, $name, type);
    } else {
      return s;
    }
  }

  // Other

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
          var delta = Math.min(.01 * (.9 - frac), 0.05);
          if (delta > 0.0001) {
            progressTimeout = setTimeout(updateProgress.bind(this, frac + delta), 20);
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

  $.postJson = function (uri, data) {
    return $.ajax({
      url: uri,
      type: 'POST',
      dataType: 'json',
      data: JSON.stringify(data),
      contentType: 'application/json'
    });
  };

});
