'use strict';

angular.module('kifi.profileService', ['kifi.routeService', 'jun.facebook'])

.factory('profileService', [
  '$http', 'env', '$q', 'util', 'routeService', '$FB', '$window',
  function ($http, env, $q, util, routeService, $FB, $window) {

    var me = {
      seqNum: 0
    };
    var addressBooks = [],
        networks = [];

    function updateMe(data) {
      angular.forEach(data, function (val, key) {
        me[key] = val;
      });
      me.picUrl = routeService.formatPicUrl(me.id, me.pictureName);
      me.primaryEmail = getPrimaryEmail(me.emails);
      me.seqNum++;
      return me;
    }

    function fetchMe() {
      return $http.get(routeService.profileUrl).then(function (res) {
        return updateMe(res.data);
      });
    }

    function getMe() {
      return me.seqNum > 0 ? $q.when(me) : fetchMe();
    }

    function postMe(data) {
      return $http.post(routeService.profileUrl, data).then(function (res) {
        return updateMe(res.data);
      });
    }

    function fetchAddressBooks() {
      return $http.get(routeService.abooksUrl).then(function (res) {
        util.replaceArrayInPlace(addressBooks, res.data);
        return addressBooks;
      });
    }

    function getAddressBooks() {
      return addressBooks.length > 0 ? $q.when(addressBooks) : fetchAddressBooks();
    }


    function getPrimaryEmail(emails) {
      return _.find(emails, 'isPrimary') || emails[0] || null;
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
      });
    }

    function getNetworks() {
      $http.get(routeService.networks).then(function (res) {
        util.replaceArrayInPlace(networks, res.data);
        me.facebookConnected = !!_.find(networks, function (n) {
          return n.network === 'facebook';
        });
        me.linkedInConnected = !!_.find(networks, function (n) {
          return n.network === 'linkedin';
        });
        me.seqNum++;
        return res.data;
      });
    }

    var social = {
      connectFacebook: function () {
        $window.location.href = routeService.linkNetwork('facebook');
      },
      connectLinkedIn: function () {
        $window.location.href = routeService.linkNetwork('linkedin');
      },
      disconnectFacebook: function () {
        return $http.post(routeService.disconnectNetwork('facebook')).then(function (res) {
          me.facebookConnected = false;
          me.seqNum++;
          return res;
        });
      },
      disconnectLinkedIn: function () {
        return $http.post(routeService.disconnectNetwork('linkedin')).then(function (res) {
          me.linkedInConnected = false;
          me.seqNum++;
          return res;
        });
      }
    };

    return {
      me: me, // when mutated, you MUST increment me.seqNum
      fetchMe: fetchMe,
      getMe: getMe,
      postMe: postMe,
      getAddressBooks: getAddressBooks,
      setNewPrimaryEmail: setNewPrimaryEmail,
      makePrimary: makePrimary,
      resendVerificationEmail: resendVerificationEmail,
      cancelPendingPrimary: cancelPendingPrimary,
      addEmailAccount: addEmailAccount,
      deleteEmailAccount: deleteEmailAccount,
      validateEmailFormat: validateEmailFormat,
      failureInputActionResult: failureInputActionResult,
      successInputActionResult: successInputActionResult,
      getEmailValidationError: getEmailValidationError,
      sendChangePassword: sendChangePassword,
      social: social,
      getNetworks: getNetworks,
      networks: networks
    };
  }
]);
