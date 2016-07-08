$(function() {
  $('.export-submit-btn, .export-resubmit-btn').on('click', sendExportRequest);
  $('.export-newemail-preclick').on('click', showNewEmailInput);
  $('.export-newemail-btn').on('click', addExportNotifyEmail);

  var EMAIL_REGEX = /^[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$/;
  var INVALID_EMAIL = 'invalid_email';

  function sendExportRequest(e) {
    e.preventDefault();
    var emailElement = $('.export-newemail-input');
    var email = emailElement && emailElement.val();
    if (!email || EMAIL_REGEX.test(email)) {
      $.postJson('/api/1/exports', { email: email })
        .success(function(response) {
          window.setTimeout(function() {
            window.location.reload(true);
          }, 500);
        });
    } else {
      showNewEmailError(INVALID_EMAIL)
    }
  }

  function showNewEmailInput() {
    $('.export-newemail-form').css('display', 'flex');
    $('.export-newemail-preclick').css('display', 'none');
  }

  function addExportNotifyEmail(e) {
    e.preventDefault();
    var email = $('.export-newemail-input').val();
    if (EMAIL_REGEX.test(email)) {
      $.postJson('/api/1/exports/addEmailToNotify', { email: email })
        .success(function(response) {
          window.setTimeout(function() {
            window.location.reload(true);
          }, 500);
        })
        .fail(function(response) {
          showNewEmailError(response.responseText);
        });
    } else {
      showNewEmailError(INVALID_EMAIL);
    }
  }

  function showNewEmailError(reason) {
    var errorElement = $('.export-newemail-error')[0];
    if (reason === INVALID_EMAIL) {
      errorElement.innerText = 'Please enter a valid email.';
    } else {
      errorElement.innerText = 'Something went wrong. Please try again.';
    }
    errorElement.style.visibility = 'visible';
  }

  $.postJson = function (uri, data) {
    return $.ajax(
      uri,
      {
        type: 'POST',
        dataType: 'json',
        data: JSON.stringify(data),
        contentType: 'application/json'
      }
    );
  };
});
