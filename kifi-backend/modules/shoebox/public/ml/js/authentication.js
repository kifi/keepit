$(function() {

  //Simple demonstration of error messages/animations

  $('.form-input').click(function() {
    $('.error').fadeOut();
    return false;
  });

  $('#login-btn').click(function() {

    $('#center_container').addClass('shake');
    $('#login-email').html('You did something wrong.');
    $('#login-email').fadeIn();
    setTimeout(function(){ $('#center_container').removeClass('shake'); }, 2000);

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

  $('#signup-btn').click(function() {

    $('#center_container').addClass('shake');
    $('#signup-lastname').html('You did something wrong.<br>Again!');
    $('#signup-lastname').fadeIn();
    setTimeout(function(){ $('#center_container').removeClass('shake'); }, 2000);

    return false;
  });

  $('#signup-complete-btn').click(function() {

    $('#center_container').addClass('shake');
    $('#signup-email').html('You did something wrong.<br><a href="https://kifi.com" class="white-link">Kifi Homepage</a>');
    $('#signup-email').fadeIn();
    setTimeout(function(){ $('#center_container').removeClass('shake'); }, 2000);

    return false;
  });

});