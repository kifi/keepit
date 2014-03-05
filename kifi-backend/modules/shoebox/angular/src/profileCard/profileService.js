'use strict';

angular.module('kifi.profileService', ['kifi.routeService'])

.factory('profileService', [
  '$http', 'env', '$q', 'util', 'routeService',
  function ($http, env, $q, util, routeService) {
    var me = {
      seqNum: 0
    };
    var addressBooks = [];

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
      // todo: add mechanism to combine concurrent outgoing requests. useful here.
      return me.seqNum > 0 ? $q.when(me) : fetchMe();
    }

    function postMe(data) {
      $http.post(routeService.profileUrl, data).then(function (res) {
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

    function setNewPrimaryEmail(me, email) {
      var props = {};
      props.emails = _.clone(me.emails, true);
      removeEmailInfo(props.emails, email);
      unsetPrimary(props.emails);
      props.emails.unshift({
        address: email,
        isPrimary: true
      });
      return postMe(props);
    }

    return {
      me: me, // when mutated, you MUST increment me.seqNum
      fetchMe: fetchMe,
      getMe: getMe,
      postMe: postMe,
      getAddressBooks: getAddressBooks,
      setNewPrimaryEmail: setNewPrimaryEmail
    };
  }
]);
