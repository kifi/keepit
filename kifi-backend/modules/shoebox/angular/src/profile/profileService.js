'use strict';

angular.module('kifi.profileService', [
  'kifi.routeService',
  'angulartics',
  'kifi.clutch'
])

.factory('profileService', [
  '$http', 'env', '$q', 'util', 'routeService', 'socialService', '$analytics', '$location', '$window', '$rootScope', 'Clutch',
  function ($http, env, $q, util, routeService, socialService, $analytics, $location, $window, $rootScope, Clutch) {

    var me = {
      seqNum: 0
    };
    var prefs = {};

    $rootScope.$on('social.updated', function () {
      fetchMe();
    });

    var meService = new Clutch(function () {
      return $http.get(routeService.profileUrl).then(function (res) {
        return updateMe(res.data);
      });
    }, {
      cacheDuration: 5000
    });

    function updateMe(data) {
      angular.forEach(data, function (val, key) {
        me[key] = val;
      });
      me.picUrl = routeService.formatPicUrl(me.id, me.pictureName);
      me.primaryEmail = getPrimaryEmail(me.emails);
      me.seqNum++;
      socialService.setExpiredTokens(me.notAuthed);
      return me;
    }

    function fetchMe() {
      meService.expireAll();
      return meService.get();
    }

    function getMe() {
      return meService.get();
    }

    function postMe(data) {
      return $http.post(routeService.profileUrl, data).then(function (res) {
        $analytics.eventTrack('user_clicked_page', {
          'action': 'updateProfile',
          'path': $location.path()
        });
        return updateMe(res.data);
      });
    }

    function setNewName(name) {
      return postMe({
        firstName: name.firstName,
        lastName: name.lastName
      });
    }

    function getPrimaryEmail(emails) {
      var actualPrimary = _.find(emails, 'isPrimary');
      if (actualPrimary) {
        return actualPrimary;
      } else {
        var placeholderPrimary = _.find(emails, 'isVerified') || emails[0] || null;
        if (placeholderPrimary) {
          _.map(emails, function (email) {
            if (email === placeholderPrimary) {
              email.isPlaceholderPrimary = true;
            }
          });
        }
        return placeholderPrimary;
      }
    }

    function removeEmailInfo(emails, addr) {
      emails = emails || me.emails;
      for (var i = emails.length - 1; i >= 0; i--) {
        if (emails[i].address === addr) {
          emails.splice(i, 1);
        }
      }
    }

    function unsetPrimary(emails) {
      var primary = getPrimaryEmail(emails);
      if (primary) {
        primary.isPrimary = false;
      }
    }

    function cloneEmails(me) {
      return { emails:  _.clone(me.emails, true) };
    }

    function setNewPrimaryEmail(email) {
      getMe().then(function (me) {
        var props = cloneEmails(me);
        removeEmailInfo(props.emails, email);
        unsetPrimary(props.emails);
        props.emails.unshift({
          address: email,
          isPrimary: true
        });
        return postMe(props);
      });
    }

    function makePrimary(email) {
      var props = cloneEmails(me);
      unsetPrimary(props.emails);
      _.find(props.emails, function (info) {
        if (info.address === email) {
          info.isPrimary = true;
        }
      });
      return postMe(props);
    }

    function resendVerificationEmail(email) {
      return $http({
        url: routeService.resendVerificationUrl,
        method: 'POST',
        params: {email: email}
      });
    }

    function cancelPendingPrimary() {
      getMe().then(function (me) {
        if (me.primaryEmail && me.primaryEmail.isPendingPrimary) {
          return deleteEmailAccount(me.primaryEmail.address);
        }
      });
    }

    function addEmailAccount(email) {
      var props = cloneEmails(me);
      props.emails.push({
        address: email,
        isPrimary: false
      });
      return postMe(props);
    }

    function deleteEmailAccount(email) {
      var props = cloneEmails(me);
      removeEmailInfo(props.emails, email);
      return postMe(props);
    }

    function validateEmailFormat(email) {
      if (!email) {
        return failureInputActionResult('This field is required');
      } else if (!util.validateEmail(email)) {
        return invalidEmailValidationResult();
      }
      return successInputActionResult();
    }

    function validateNameFormat(name) {
      if (!name) {
        return failureInputActionResult('This field is required');
      }

      return successInputActionResult();
    }

    function failureInputActionResult(errorHeader, errorBody) {
      return {
        isSuccess: false,
        error: {
          header: errorHeader,
          body: errorBody
        }
      };
    }

    function successInputActionResult() {
      return {isSuccess: true};
    }

    function getEmailValidationError(status) {
      switch (status) {
      case 400: // bad format
        return invalidEmailValidationResult();
      case 403: // belongs to another user
        return failureInputActionResult(
          'This email address is already taken',
          'This email address belongs to another user.<br>Please enter another email address.'
        );
      }
    }

    function invalidEmailValidationResult() {
      return failureInputActionResult('Invalid email address', 'Please enter a valid email address');
    }

    function sendChangePassword(oldPassword, newPassword) {
      return $http.post(routeService.userPasswordUrl, {
        oldPassword: oldPassword,
        newPassword: newPassword
      }).then(function () {
        $analytics.eventTrack('user_clicked_page', {
          'action': 'changePassword',
          'path': $location.path()
        });
      });
    }

    function fetchPrefs() {
      return $http.get(routeService.prefs).then(function (p) {
        util.replaceObjectInPlace(prefs, p.data);
        return p.data;
      });
    }

    function logout() {
      $window.location = routeService.logout;
    }

    function postDelightedAnswer(score, comment, answerId) {
      var data = {
        score: score || undefined,
        comment: comment || undefined,
        answerId: answerId || undefined
      };
      return $http.post(routeService.postDelightedAnswer, data).then(function (res) {
        return res.data && res.data.answerId;
      });
    }

    function cancelDelightedSurvey() {
      return $http.post(routeService.cancelDelightedSurvey, {});
    }

    function closeAccountRequest(data) {
      return $http.post(routeService.userCloseAccount, data);
    }

    return {
      me: me, // when mutated, you MUST increment me.seqNum
      fetchMe: fetchMe,
      getMe: getMe,
      postMe: postMe,
      logout: logout,
      fetchPrefs: fetchPrefs,
      prefs: prefs,
      setNewName: setNewName,
      setNewPrimaryEmail: setNewPrimaryEmail,
      makePrimary: makePrimary,
      resendVerificationEmail: resendVerificationEmail,
      cancelPendingPrimary: cancelPendingPrimary,
      addEmailAccount: addEmailAccount,
      deleteEmailAccount: deleteEmailAccount,
      validateEmailFormat: validateEmailFormat,
      validateNameFormat: validateNameFormat,
      failureInputActionResult: failureInputActionResult,
      successInputActionResult: successInputActionResult,
      getEmailValidationError: getEmailValidationError,
      sendChangePassword: sendChangePassword,
      postDelightedAnswer: postDelightedAnswer,
      cancelDelightedSurvey: cancelDelightedSurvey,
      closeAccountRequest: closeAccountRequest
    };
  }
]);
