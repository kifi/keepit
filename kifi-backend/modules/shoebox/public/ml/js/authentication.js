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

    var validEmail = validateEmailAddress($email);
    var validPassword = validatePassword($password);
    if (!validEmail || !validPassword) {
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
      // errors I know about:
      // no_such_user
      // wrong_password
      var body = xhr.responseJSON || {};
      console.error(body)
      if (body.error === 'no_such_user') {
        $('.login-email').focus().select();
        error($('#error-login-email'), 'Whoops, we can’t find you.<br>Try a different email or social account?');
      } else if (body.error === 'wrong_password') {
        $('.login-password').focus().select();
        error($('#error-login-password'), 'Wrong password. Forgot it?<br><a href="#">Reset it here</a>.');
      } else {
        // ...
      }
      console.log(xhr);
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

    var validEmail = validateEmailAddress($email);
    var validPassword = validatePassword($password);
    if (!validEmail || !validPassword) {
      //error()
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
      // errors I know about:
      // password_too_short
      error();
      console.log(xhr);
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

    var validFirstName = validateName($firstName);
    var validLastName = validateName($lastName);
    if (!validFirstName || !validLastName) {
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
      // not sure about errors for this
      error();
      console.log(xhr);
    });
    return false;
  };
  $('.signup-name').submit(kifi.signupGetName);

  // Sign up social account (signup 2), needs email
  kifi.signupGetEmail = function (e) {
    e.preventDefault();
    var $form = $(this);
    var $email = $form.find('.email');

    var validEmail = validateEmailAddress($email);
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
      } else {
        console.log(data);
      }
    }).fail(function (xhr) {
      // not sure about errors for this
      error($('.error-signup-email'), 'Whoops!');
      console.log(xhr);
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
    var validEmail = validateEmailAddress($email);
    var emailError = $('#error-fp');

    if (!validEmail) {
      error(emailError, 'Sorry invalid email');
      $('.fp-input').focus().select();
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
    .fail(function () {
      // TRACK
      var emailError = $('#error-fp');
      error(emailError, 'Sorry, we don’t recognize this email address.');
      $('.fp-input').focus().select();
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
  function error(errorField, errorHtml) {
    $('#center_container').addClass('shake');
    errorField.html(errorHtml).fadeIn();
    setTimeout(function(){ $('#center_container').removeClass('shake'); }, 2000);
  }

  function hideError() {
    $('.error').fadeOut();
  }

  var emailAddrRe = /^[a-zA-Z0-9.!#$%&'*+\/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+$/;
  function validateEmailAddress($email) { // pass email object
    var s = $email.val();
    if (!s) {
      // (todo) TRACK
      error($email, 'Please enter your email address');
    } else if (!emailAddrRe.test(s)) {
      // (todo) TRACK
      error($email, 'Invalid email address')
    }
    return s;
  }

  function validatePassword($password) {
    var s = $password.val();
    if (!s) {
      // (todo) TRACK
      error($password, 'Please enter your password');
    } else if (s.length < 7) {
      // (todo) TRACK
      error($password, 'Password too short');
    } else {
      return s;
    }
  }

  function validateName($name) {
    var s = $.trim($name.val());
    if (!s) {
      // (todo) TRACK
      error($name, 'Name is required');
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
