'use strict';

angular.module('kifi')

.factory('profileService', [
  '$http', 'net', 'env', '$q', 'util', 'routeService', 'socialService', '$analytics',
  '$location', '$window', '$rootScope', 'Clutch', '$rootElement',
  function ($http, net, env, $q, util, routeService, socialService, $analytics,
            $location, $window, $rootScope, Clutch, $rootElement) {
    var initialized = false;

    var me = {};
    var prefs = {};
    var userLoggedIn; // undefined means we don't know the status yet

    $rootScope.$on('social.updated', function () {
      fetchMe();
    });

    function updateLoginState(loggedIn, broadcast) {
      $rootElement.find('html').removeClass('kf-logged-in kf-logged-out').addClass(loggedIn ? 'kf-logged-in' : 'kf-logged-out');
      $rootScope.userLoggedIn = userLoggedIn = loggedIn;

      // Do not broadcast 'userLoggedInStateChange' on the first call.
      if (initialized && broadcast) {
        $rootScope.$broadcast('userLoggedInStateChange', loggedIn);
      }

      initialized = true;
    }

    var meService = new Clutch(function () {
      var oldMeId = me.id;
      var promise = !$window.initialMePromise ? $http.get(routeService.profileUrl) : $q.when($window.initialMePromise).then(function (data) {
        delete $window.initialMePromise;
        return { data : data };
      });

      return promise
      .then(function (res) {
        updateMe(res.data);
        updateLoginState(true, me.id !== oldMeId);
        return me;
      })['catch'](function (err) {
        if (err.status === 403) {
          util.replaceObjectInPlace(me, {});
          updateLoginState(false, me.id !== oldMeId);
        }
      });
    }, {
      cacheDuration: 5000
    });

    function updateMe(data) {
      angular.forEach(data, function (val, key) {
        me[key] = val;
      });

      me.primaryEmail = getPrimaryEmail(me.emails);
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
      emails = emails || [];
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
      emails = emails || me.emails || [];
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

    function sendMemberConfirmationEmail(orgId, email) {
      return net.sendMemberConfirmationEmail(orgId, { email: email });
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

    function changeBiography(newBiography) {
      return $http.post(routeService.userBiography, {
        biography: newBiography
      }).then(function (resp) {
        if (resp.data) {
          me.biography = newBiography;
          return newBiography;
        }
      });
    }

    function fetchPrefs(invalidateCache) {
      if (invalidateCache) {
        net.fetchPrefs.clearCache();
      }
      return net.fetchPrefs().then(function (p) {
        util.replaceObjectInPlace(prefs, p.data);
        $rootScope.$emit('prefsChanged');
        return p.data;
      });
    }

    function savePrefs(newPrefObject) {
      if (!_.isEmpty(newPrefObject)) {
        return $http.post(routeService.prefs, newPrefObject).then(function (p) {
          _.assign(prefs, p.data);
          $rootScope.$emit('prefsChanged');
          return p.data;
        });
      }
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

    function getUserLoggedIn() {
      return userLoggedIn;
    }

    function getSettings() {
      return $http.get(routeService.profileSettings);
    }

    function setSettings(changedSettings) {
      return $http.post(routeService.profileSettings, changedSettings);
    }

    function hideOrgDomain(org) {
      return net.hideOrgDomain(org.id).then(fetchMe);
    }

    function isAdmin() {
      return me.experiments && me.experiments.indexOf('admin') > -1;
    }

    function isFakeUser() {
      return me.experiments && me.experiments.indexOf('fake') > -1;
    }

    function hasExperiment(experiment) {
      return me.experiments && me.experiments.indexOf(experiment) > -1;
    }

    function shouldBeWindingDown() {
      return hasExperiment('system_read_only') || hasExperiment('system_export_only');
    }

    return {
      initLoggedOut: function () {
        updateLoginState(false);
      },
      userLoggedIn: getUserLoggedIn,
      me: me,
      getSettings: getSettings,
      setSettings: setSettings,
      fetchMe: fetchMe,
      getMe: getMe,
      prefs: prefs,
      fetchPrefs: fetchPrefs,
      savePrefs: savePrefs,
      setNewName: setNewName,
      setNewPrimaryEmail: setNewPrimaryEmail,
      makePrimary: makePrimary,
      resendVerificationEmail: resendVerificationEmail,
      sendMemberConfirmationEmail: sendMemberConfirmationEmail,
      cancelPendingPrimary: cancelPendingPrimary,
      changeBiography: changeBiography,
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
      closeAccountRequest: closeAccountRequest,
      hideOrgDomain: hideOrgDomain,
      isAdmin: isAdmin,
      isFakeUser: isFakeUser,
      hasExperiment: hasExperiment,
      shouldBeWindingDown: shouldBeWindingDown
    };
  }
]);
