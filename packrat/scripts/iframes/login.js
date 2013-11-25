if (/^Mac/.test(navigator.platform)) {
  document.documentElement.className = 'mac';
}

document.addEventListener('keydown', function (e) {
  if (e.keyCode === 27 && !e.metaKey && !e.ctrlKey && !e.altKey && !e.shiftKey) {  // Esc
    var handler = $(document).data('esc');
    if (handler) {
      handler();
    } else {
      parent.postMessage({close: true}, '*');
    }
    return false;
  }
});

(function (withJQuery) {
  'use strict';

  var s = document.createElement('SCRIPT');
  s.src = '//connect.facebook.net/en_US/all.js';
  s.id = 'facebook-jssdk';
  document.head.appendChild(s);

  s = document.createElement('SCRIPT');
  s.src = '//platform.linkedin.com/in.js';
  s.textContent = ['api_key:r11loldy9zlg', 'onLoad:onLoadLinkedInApi', 'authorize:true'].join('\n');
  document.head.appendChild(s);

  window.fbAsyncInit = function () {
    FB.Event.subscribe('auth.statusChange', function (o) {
      var id = o && o.authResponse && o.authResponse.userID;
      id && showNetworkPic('facebook', 'https://graph.facebook.com/' + id + '/picture?return_ssl_resources=1&width=50&height=50');
    });
    FB.init({appId: 104629159695560, status: true});
  };

  window.onLoadLinkedInApi = function () {
    IN.User.isAuthorized() && IN.API.Profile('me').fields('picture-url;secure=true').result(function (o) {
      var url = o && o.values && o.values[0] && o.values[0].pictureUrl;
      url && showNetworkPic('linkedin', url);
    });
  };

  function showNetworkPic(network, url) {
    var img = new Image;
    img.onload = function () {
      var parEl = document.querySelector('.form-network.' + network + '>.form-network-icon');
      parEl.innerHTML = '';
      parEl.appendChild(this);
    };
    img.className = 'form-network-pic';
    img.src = url;
  }

  if (window.$) {
    withJQuery();
  } else {
    document.head.querySelector('script[src$="jquery.js"]').addEventListener('load', withJQuery);
  }
}(function () {
  'use strict';

  (function () {
    var s = document.createElement('SCRIPT');
    s.src = this.src.replace('jquery', 'jquery-ui-position');
    document.body.appendChild(s);
  }).call(this);

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

  $('.form-network').click(function () {
    parent.postMessage({url: this.href}, '*');
    return false;
  });

  var emailAddrRe = /^[a-zA-Z0-9.!#$%&'*+\/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$/;
  function showError($in, msg, opts) {
    var $err = $('<div class=form-error>').css('visibility', 'hidden').html(msg).appendTo('body')
      .position({my: 'left top', at: 'left bottom+10', of: $in, collision: 'fit none'})
      .css('visibility', '')
      .delay(opts && opts.ms || 1000).fadeOut(300, removeError);
    $in.blur();  // closes browser autocomplete suggestion list
    $in.focus().select().on('input blur', removeError);
    return $err;
    function removeError() {
      $err.remove();
      $in.off('input blur', removeError);
    }
  }
  function validateEmailAddress($in) {
    var s = $.trim($in.val());
    if (!s) {
      showError($in, 'Please enter your email address');
    } else if (!emailAddrRe.test(s)) {
      showError($in, 'Invalid email address');
    } else {
      return s;
    }
  }
  function validatePassword($in) {
    var s = $in.val();
    if (!s) {
      showError($in, 'Please enter your password');
    } else if (s.length < 7) {
      showError($in, 'Password too short', {ms: 1500});
    } else {
      return s;
    }
  }

  var $loginForm = $('.form-login').submit(onLoginFormSubmit)
  .on('click', '.password-forgot', function (e) {
    if (e.which !== 1) return;
    resetPasswordDialog.show($loginForm.find('.form-email-addr').val());
  });
  function onLoginFormSubmit(e) {
    var $form = $(this);
    var promise = $form.data('promise');
    if (promise && promise.state() === 'pending') {
      return false;
    }
    $('.form-error').remove();
    var $email = $form.find('.form-email-addr');
    var $password = $form.find('.form-password');
    var email = validateEmailAddress($email);
    var password = email && validatePassword($password);
    if (email && password) {
      $form.data('promise', $.postJson(this.action, {
        username: email,
        password: password
      })
      .done(function () {
        parent.postMessage({authenticated: true}, '*');
      })
      .fail(function (xhr) {
        if (xhr.status === 403) {
          var o = xhr.responseJSON;
          if (o.error === 'no_such_user') {
            showError($email, 'No account with this email address', {ms: 1500});
          } else {
            showError($password,
              '<div class=form-error-title>Incorrect password</div>' +
              '<div class=form-error-explanation>To choose a new password,<br>click “Forgot password”.</div>',
             {ms: 2500});
          }
        } else {
          // TODO: offline? 500?
        }
      }));
    }
    return false;
  }
  $loginForm.find('.form-email-addr').focus();

  $('.no-account>a').click(function (e) {
    if (e.which !== 1) return;
    parent.postMessage({close: true}, '*');
  });

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
        $(document).data('esc', hide);
      }
    };

    function hide() {
      $(document).removeData('esc');
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
      var email = validateEmailAddress($email);
      if (email) {
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
            showError($email, 'Sorry, we don’t recognize this email address.', {ms: 2000});
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
}));
