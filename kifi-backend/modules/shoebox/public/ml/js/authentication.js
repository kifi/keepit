$(function() {

  //
  // Listeners
  //

  //Simple demonstration of error messages/animations
  $('.form-input').on('click keypress', hideError);

  /* General Modal Functionalities */
  $('.modal-overlay').on('click', function() {
    if (event.target.className === 'modal-overlay' || event.target.className === 'modal-cell') {
      hideModal();
    }
  });
  $('.modal-x').click(hideModal);
  $('.modal-button-cancel').click(hideModal);

  /* Forgot password */
  $('.forgot-password-link').click(showForgotPasswordModal);
  $('.forgot-password-modal .modal-x').click(resetForgotPasswordModal);
  $('.forgot-password-modal .modal-button-cancel').click(resetForgotPasswordModal);
  $('.forgot-password-modal .modal-button-action').click(submitForgotPassword);

  /* Signup With Email */
  $('#upload-image-btn').click(function() {
    $("#photo-upload-step2").fadeIn();
    $("#photo-upload-step1").fadeOut();
    return false;
  });

  $('#back-btn').click(function() {
    $("#photo-upload-step2").fadeOut();
    $("#photo-upload-step1").fadeIn();
    return false;
  });


  //$('#signup-complete-btn').click(function() {
  //
  //  $('#center_container').addClass('shake');
  //  $('#signup-email').html('You did something wrong.<br><a href="https://kifi.com" class="white-link">Kifi Homepage</a>');
  //  $('#signup-email').fadeIn();
  //  setTimeout(function(){ $('#center_container').removeClass('shake'); }, 2000);
  //
  //  return false;
  //});


  var kifi = {};

  // Log in with email/password
  kifi.loginWithEmail = function (e) {
    e.preventDefault();
    var $form = $(this);
    var $email = $form.find('.login-email');
    var $password = $form.find('.login-password');

    var validEmail = validateEmailAddress($email, $form.find('#error-login-email'));
    if (!validEmail) {
      return;
    }
    var validPassword = validatePassword($password, $form.find('#error-login-password'));
    if (!validPassword) {
      return;
    }

    $.postJson(this.action, {
      'username': validEmail,
      'password': validPassword
    }).done(function (data) {
      if (data.uri) {
        window.location = data.uri;
      } else {
        console.log(data);
      }
    }).fail(function (xhr) {
      // possible errors: no_such_user, wrong_password, bad_form (bad email, bad password, etc.)
      var body = xhr.responseJSON || {};
      var $email = $('.form-input.signup-email');
      var $password = $('.form-input.signup-password');
      if (body.error === 'no_such_user') {
        errorUserNotFound($('#error-login-email'), $email);
      } else if (body.error === 'wrong_password') {
        errorWrongPassword($('#error-login-password'), $password);
      } else {
        errorUnknown($('#login-email'), $email);
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

    var validEmail = validateEmailAddress($email, $form.find('#login-email'));
    if (!validEmail) {
      return;
    }
    var validPassword = validatePassword($password, $form.find('#login-password'));
    if (!validPassword) {
      return;
    }

    $.postJson(this.action, {
      'email': validEmail,
      'password': validPassword
    }).done(function (data) {
      if (data.success) { // successes return: {success: true}
        window.location = '/new/signupName'; // todo: change to /signup
      } else {
        console.log(data);
      }
    }).fail(function (xhr) {
      // possible errors: error.email, password_too_short, user_exists_failed_auth
      var body = xhr.responseJSON || {};
      var $email = $('.form-input.signup-email');
      var $password = $('.form-input.signup-password');
      if (body.error === "error.email") {
        errorInvalidEmail($('#login-email'), $email);
      } else if (body.error === "password_too_short") {
        errorShortPassword($('#login-password'), $password);
      } else if (body.error === "user_exists_failed_auth") { // account already exists but incorrect password
        errorUserExists($('#login-email'), $email);
      } else {
        errorUnknown($('#login-email'), $email);
      }
    });
    return false;
  };
  $('.signup-email-pass').submit(kifi.signupWithEmailPassword);

  // Sign up with email/password (sign up 2), needs name
  kifi.signupGetName = function (e) {
    e.preventDefault();
    var $form = $(this);
    var $firstName = $form.find('.first-name');
    var $lastName = $form.find('.last-name');

    var validFirstName = validateName($firstName, $form.find('#signup-firstname'), 'first');
    if (!validFirstName) {
      return;
    }
    var validLastName = validateName($lastName, $form.find('#signup-lastname'), 'last');
    if (!validLastName) {
      return;
    }

    $.postJson(this.action, {
      firstName: validFirstName,
      lastName: validLastName,
      picToken: undefined, // upload && upload.token
      picWidth: undefined, // pic.width
      picHeight: undefined, // pic.height
      cropX: undefined, // pic.x
      cropY: undefined, // pic.y
      cropSize: undefined // pic.size
    }).done(function (data) {
      if (data.uri) { // successes return: {success: true}
        window.location = data.uri;
      } else {
        console.log(data);
      }
    }).fail(function (xhr) {
      var $form = $('.form-input.first-name');
      errorUnknown('#signup-firstname', $form);
    });
    return false;
  };
  $('.signup-name').submit(kifi.signupGetName);

  // Sign up social account (signup 2), needs email
  kifi.signupGetEmail = function (e) {
    e.preventDefault();
    var $form = $(this);
    var $email = $form.find('.email');

    var validEmail = validateEmailAddress($email, $form.find('#error-signup-email'));
    if (!validEmail) {
      return;
    }

    $.postJson(this.action, {
      email: validEmail
    }).done(function (data) {
      console.log(data);
      return;
      if (data.uri) { // successes return: {success: true}
        window.location = data.uri;
      }
    }).fail(function (xhr) {
      var body = xhr.responseJSON || {};
      var $form = $('.form-input.email');
      if (body.error === 'error.email') {
        errorInvalidEmail($('#error-signup-email'), $form);
      } else if (body.error === 'error.required') {
        errorUnrecognizedEmail($('#error-signup-email'), $form);
      } else {
        errorUnknown($('#error-signup-email'), $form);
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
    $('.modal-overlay.forgot-password').addClass('show');
    var modal = $('.forgot-password-modal');
    modal.find('.fp-form').show();
    modal.find('.fp-success').hide();
  }

  function submitForgotPassword() {
    event.preventDefault();

    // validate email
    var $email = $('.fp-input');
    var validEmail = validateEmailAddress($email, $('#error-fp'));

    if (!validEmail) {
      return;
    }
    // make request
    var actionUri = $('.fp-form')[0].action;
    $.postJson(actionUri, {email: validEmail})
    .done(function () {
      // TRACK
      // show success pane, hide original pane
      var modal = $('.forgot-password-modal');
      modal.find('.fp-address').html(validEmail);
      modal.find('.fp-form').hide();
      modal.find('.fp-success').show();
    })
    .fail(function (xhr) { // errors: no_account
      var body = xhr.responseJSON || {};
      if (body.error === 'no_account') {
        errorUnrecognizedEmail($('#error-fp'), $('.fp-input'));
      } else {
        errorUnknown($('#error-fp'), $('.fp-input'));
      }
    });
  }

  function resetForgotPasswordModal() {
    hideModal();
    var modal = $('.forgot-password-modal');
    modal.find('.fp-form').show();
    modal.find('.fp-success').hide();
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
  function errorEmptyPassword($errorField, $inputField) {
    // TRACK
    error($errorField, 'Please enter your password', $inputField);
  }
  function errorShortPassword($errorField, $inputField) {
    // TRACK
    error($errorField, 'Password is too short', $inputField);
  }
  function errorEmptyName($errorField, whichName, $inputField) {
    // TRACK
    error($errorField, 'Your '+ whichName +' name is required', $inputField);
  }
  function errorEmptyEmail($errorField, $inputField) {
    // TRACK
    error($errorField, 'Please enter your email address', $inputField);
  }
  function errorInvalidEmail($errorField, $inputField) {
    // TRACK
    error($errorField, 'Invalid email address', $inputField);
  }
  function errorUserNotFound($errorField, $inputField) {
    // TRACK
    error($errorField, 'Whoops, we can’t find you.<br>Try a different email or social account?', $inputField);
  }
  function errorUnrecognizedEmail($errorField, $inputField) {
    // TRACK
    error($errorField, 'Sorry, we don’t recognize this email address.', $inputField);
  }
  function errorWrongPassword($errorField, $inputField) {
    // TRACK
    error($errorField, 'Wrong password. Forgot it?<br><a href="#">Reset it here</a>.', $inputField);
  }
  function errorUserExists($errorField, $inputField) {
    // TRACK
    error($errorField, 'An account already exists for this email!<br>Try <a href="/login">Logging In</a>', $inputField);
  }
  function errorUnknown($errorField, $inputField) {
    // TRACK
    error($errorField, 'Unknown Error:<br>Please contact us on <a href="http://support.kifi.com/hc/en-us/requests/new">Support</a>', $inputField);
  }


  //
  // Validation functions
  //
  var emailAddrRe = /^[a-zA-Z0-9.!#$%&'*+\/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+$/;
  function validateEmailAddress($email, $errorObj) { // pass email object & where error object should appear
    var s = $email.val();
    if (!s) {
      errorEmptyEmail($errorObj, $email);
    } else if (!emailAddrRe.test(s)) {
      errorInvalidEmail($errorObj, $email);
    } else {
      return s;
    }
  }

  function validatePassword($password, $errorObj) {
    var s = $password.val();
    if (!s) {
      errorEmptyPassword($errorObj, $password);
    } else if (s.length < 7) {
      errorShortPassword($errorObj, $password);
    } else {
      return s;
    }
  }

  function validateName($name, $errorObj, whichName) {
    var s = $.trim($name.val());
    if (!s) {
      errorEmptyName($errorObj, whichName, $name);
    } else {
      return s;
    }
  }

  $.postJson = function (uri, data) {
    console.log(uri, data);
    return $.ajax({
      url: uri,
      type: 'POST',
      dataType: 'json',
      data: JSON.stringify(data),
      contentType: 'application/json'
    });
  };

});
