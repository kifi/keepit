$(function() {

  //Simple demonstration of error messages/animations

  $('.form-input').click(function() {
    $('.error').fadeOut();
    return false;
  });


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

  //$('#signup-btn').click(function() {
  //
  //  $('#center_container').addClass('shake');
  //  $('#signup-lastname').html('You did something wrong.<br>Again!');
  //  $('#signup-lastname').fadeIn();
  //  setTimeout(function(){ $('#center_container').removeClass('shake'); }, 2000);
  //
  //  return false;
  //});

  $('#signup-complete-btn').click(function() {

    $('#center_container').addClass('shake');
    $('#signup-email').html('You did something wrong.<br><a href="https://kifi.com" class="white-link">Kifi Homepage</a>');
    $('#signup-email').fadeIn();
    setTimeout(function(){ $('#center_container').removeClass('shake'); }, 2000);

    return false;
  });


  var kifi = {};

  // Log in with email/password
  kifi.loginWithEmail = function (e) {
    e.preventDefault();
    var $form = $(this);
    var $email = $form.find('.login-email');
    var $password = $form.find('.login-password');

    // todo: Validate input

    $.postJson(this.action, {
      'username': $email.val(),
      'password': $password.val()
    }).done(function (data) {
      if (data.uri) {
        window.location = data.uri;
      } else {
        console.log(data);
      }
    }).fail(function (xhr) {
      // errors I know about:
      // wrong_password
      error();
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

    // todo: Validate input

    $.postJson(this.action, {
      'email': $email.val(),
      'password': $password.val()
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

    // todo: Validate input

    $.postJson(this.action, {
      firstName: $firstName.val() || '',
      lastName: $lastName.val() || '',
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

  // Utilities
  function error(errorHtml) {
    $('#center_container').addClass('shake');
    $('#login-email').html(errorHtml).fadeIn();
    setTimeout(function(){ $('#center_container').removeClass('shake'); }, 2000);
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