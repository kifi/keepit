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

  var creds = {}, on = {creds: function () {}};
  var appIds = location.hash.substr(1).split('&').reduce(function (o, s) {
    var arr = s.split('=');
    o[arr[0]] = arr[1];
    return o;
  }, {});

  window.fbAsyncInit = function () {
    FB.Event.subscribe('auth.statusChange', function (o) {
      var r = o && o.authResponse;
      var id = r && r.userID;
      if (id) {
        pics.show('facebook', 'https://graph.facebook.com/' + id + '/picture?return_ssl_resources=1&width=50&height=50');
        on.creds('facebook', creds.facebook = r);
      } else {
        pics.hide('facebook');
      }
    });
    FB.init({appId: appIds.facebook, status: true, version: 'v2.0'});
  };

  window.onLoadLinkedInApi = function () {
    IN.Event.on(IN, 'auth', function (o) {
      IN.API.Profile('me').fields(['id','picture-url;secure=true']).result(function (o) {
        var r = o && o.values && o.values[0];
        if (r.pictureUrl) {
          pics.show('linkedin', r.pictureUrl);
        } else {
          pics.hide('linkedin');
        }
        var cookieName = 'linkedin_oauth_' + appIds.linkedin;
        var cookie = readCookie(cookieName);
        if (cookie) {
          on.creds('linkedin', creds.linkedin = JSON.parse(cookie));
        }
        clearCookie(cookieName);
        clearCookie(cookieName + '_crc');
      });
    });

    function readCookie(name) {
      var v = new RegExp('(?:^|; )' + name + '=([^;]*)').exec(document.cookie);
      return v && decodeURIComponent(v[1]);
    }

    function clearCookie(name) {
      document.cookie = name + '=; expires=' + new Date(0).toUTCString();
    }
  };

  var pics = {
    show: function (nw, url) {
      var img = new Image;
      img.onload = function () {
        var parEl = document.querySelector('.' + nw + '>.form-network-icon');
        parEl.innerHTML = '';
        parEl.appendChild(this);
      };
      img.className = 'form-network-pic';
      img.src = url;
    },
    hide: function (nw) {
      document.querySelector('.' + nw + '>.form-network-icon').innerHTML = '';
    }
  };

  var s = document.createElement('SCRIPT');
  s.src = '//connect.facebook.net/en_US/sdk.js';
  s.id = 'facebook-jssdk';
  document.head.appendChild(s);

  s = document.createElement('SCRIPT');
  s.src = '//platform.linkedin.com/in.js';
  s.textContent = ['api_key:' + appIds.linkedin, 'onLoad:onLoadLinkedInApi', 'authorize:true', 'credentials_cookie:true'].join('\n');
  document.head.appendChild(s);

  var proceed = withJQuery.bind(null, creds, pics, on);
  var loading = Array.prototype.slice.call(document.head.querySelectorAll('script[data-loading=true]'));
  if (loading.length === 0) {
    proceed();
  } else {
    var onLoad = function () {
      if (--loading.length === 0) {
        proceed();
      }
    };
    loading.forEach(function (s) {
      s.addEventListener('load', onLoad);
    });
  }
}(function (creds, pics, on) {
  'use strict';

  $('<script>')
  .prop('src', document.head.querySelector('script[src$="jquery.js"]').src.replace('jquery', 'jquery-ui-position'))
  .appendTo('body');

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

  var $a = $('.form-network').click(function () {
    var now = Date.now();
    var data = $(this).data();
    if (now - (data.clickedAt || 0) > 1000) {
      data.clickedAt = now;
      var c = creds[data.nw];
      if (c) {
        authenticateViaNetwork(data, c);
      } else {
        showNetworkLoginPopup(data);
      }
    }
    return false;
  });
  on.creds = function (nw, nwCreds) {
    var data = $a.filter('.' + nw).data();
    if (data.clickedAt) {
      authenticateViaNetwork(data, nwCreds);
    }
  };
  function authenticateViaNetwork(data, nwCreds) {
    $.postJson('/ext/auth/' + data.nw, nwCreds)
    .done(function () {
      parent.postMessage({authenticated: true}, '*');
    })
    .fail(function (xhr) {
      if ((xhr.responseJSON || {}).error === 'user_not_found') {
        parent.postMessage({path: '/login/' + data.nw}, '*');
      } else {
        delete creds[data.nw];
        pics.hide(data.nw);
        if (data.clickedAt > (data.poppedAt || 0)) {
          showNetworkLoginPopup(nw);
        }
      }
    });
  }
  function showNetworkLoginPopup(data) {
    data.poppedAt = Date.now();
    switch (data.nw) {
      case 'facebook':
        FB.login($.noop, {scope: 'email'});
        break;
      case 'twitter':
        window.open('/login/twitter?close=1', '_blank', 'width=450,height=480,resizable,scrollbars=yes,status=1').focus();
        window.addEventListener('message', function (e) {
          if (e.data === 'authed' && e.origin === location.origin) {
            parent.postMessage({authenticated: true}, '*');
          }
        });
        break;
      case 'linkedin':
        IN.UI.Authorize().place();
        break;
    }
  }

  var emailAddrRe = /^[a-zA-Z0-9.!#$%&'*+\/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$/;
  function showError($in, msg) {
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
      showError($in, 'Password too short');
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
            showError($email, 'No account with this email address');
          } else {
            showError($password,
              '<div class=form-error-title>Incorrect password</div>' +
              '<div class=form-error-explanation>To choose a new password,<br>click “Forgot password”.</div>');
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
            showError($email, 'Sorry, we don’t recognize this email address.');
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
