var kifi = {};
kifi.form = (function () {
  'use strict';
  var emailAddrRe = /^[a-zA-Z0-9.!#$%&'*+\/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$/;
  return {
    showError: function ($in, msg) {
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
    },
    validateEmailAddress: function ($in) {
      var s = $.trim($in.val());
      if (!s) {
        Tracker.track('visitor_viewed_page', { error: 'noEmail' });
        kifi.form.showError($in, 'Please enter your email address');
      } else if (!emailAddrRe.test(s)) {
        Tracker.track('visitor_viewed_page', { error: 'invalidEmail', errorValue: s });
        kifi.form.showError($in, 'Invalid email address');
      } else {
        return s;
      }
    },
    validateNewPassword: function ($in) {
      var s = $in.val();
      if (!s) {
        Tracker.track('visitor_viewed_page', { error: 'noNewPassword'});
        kifi.form.showError($in, 'Please choose a password<br>for your account');
      } else if (s.length < 7) {
        Tracker.track('visitor_viewed_page', { error: 'shortNewPassword', errorValue: s.length });
        kifi.form.showError($in, 'Password must be at least 7 characters');
      } else {
        return s;
      }
    },
    validatePassword: function ($in) {
      var s = $in.val();
      if (!s) {
        Tracker.track('visitor_viewed_page', { error: 'noPassword' });
        kifi.form.showError($in, 'Please enter your password');
      } else if (s.length < 7) {
        Tracker.track('visitor_viewed_page', { error: 'shortPassword', errorValue: s.length });
        kifi.form.showError($in, 'Password too short');
      } else {
        return s;
      }
    },
    validateName: function ($in) {
      var s = $.trim($in.val());
      if (!s) {
        Tracker.track('visitor_viewed_page', { error: 'noName' });
        kifi.form.showError($in,
          '<div class=form-error-title>Name is required</div>' +
          '<div class=form-error-explanation>We need your name so that<br>your friends will be able to<br>communicate with you</div>');
      } else {
        return s;
      }
    }
  };
}());
