'use strict';

angular.module('kifi')

.factory('keepActionService', [
  '$analytics', '$location', 'net', 'libraryService',
  function ($analytics, $location, net, libraryService) {

    function sanitizeUrl(url) {
      var regex = /^https?:\/\//;
      if (!regex.test(url)) {
        return 'http://' + url;
      } else {
        return url;
      }
    }

    function keepToLibrary(keepInfos, libraryId) {
      $analytics.eventTrack('user_clicked_page', {
        // TODO(yiping): should we have a different action
        // for keeping to library?
        'action': 'keep',
        'path': $location.path()
      });

      var data = {
        keeps: keepInfos.map(function(keep) {
          var keepData = { url: sanitizeUrl(keep.url) };
          if (keep.title) { keepData.title = keep.title; }
          return keepData;
        })
      };

      return net.addKeepsToLibrary(libraryId, data).then(function (res) {
        libraryService.noteLibraryKeptTo(libraryId);
        libraryService.addToLibraryCount(libraryId, keepInfos.length);

        _.uniq(res.data.keeps, function (keep) {
          return keep.url;
        });

        return res.data;
      });
    }

    function copyToLibrary(keepIds, libraryId, galleryView) {
      var props = {
        // TODO(yiping): should we have a different action
        // for keeping to library?
        'action': 'keep',
        'path': $location.path()
      };
      if (galleryView !== undefined) { props.keepView = galleryView ? 'gallery' : 'list'; }
      $analytics.eventTrack('user_clicked_page', props);

      var data = {
        to: libraryId,
        keeps: keepIds
      };

      return net.copyKeepsToLibrary(data).then(function (res) {
        libraryService.noteLibraryKeptTo(libraryId);

        _.uniq(res.data.keeps, function (keep) {
          return keep.url;
        });

        return res.data;
      });
    }

    function moveToLibrary(keepIds, libraryId) {
      $analytics.eventTrack('user_clicked_page', {
        // TODO(yiping): should we have a different action
        // for keeping to library?
        'action': 'keep',
        'path': $location.path()
      });

      var data = {
        to: libraryId,
        keeps: keepIds
      };

      return net.moveKeepsToLibrary(data).then(function (res) {
        libraryService.noteLibraryKeptTo(libraryId);

        _.uniq(res.data.keeps, function (keep) {
          return keep.url;
        });

        return res.data;
      });
    }

    function editKeepTitle(keepPubId, newTitle) {
      return net.modifyKeep(keepPubId, { title: newTitle }).then(function () {
        return newTitle;
      });
    }

    // When a url is added as a keep, the returned keep does not have the full
    // keep information we need to display it. This function fetches that
    // information.
    function fetchFullKeepInfo(keep) {
      return net.getKeep(keep.id).then(function (result) {
        return _.assign(keep, result.data);
      });
    }

    function getFullKeepInfo(id, authToken, maxMessagesShown) {
      var params = maxMessagesShown && {maxMessagesShown: maxMessagesShown};
      return net.getKeep(id, authToken, params).then(function (result) {
        return result.data;
      });
    }

    function unkeepFromLibrary(libraryId, keepId) {
      libraryService.expireKeepsInLibraries();
      return net.removeKeepFromLibrary(libraryId, keepId);
    }

    function unkeepManyFromLibrary(libraryId, keeps) {
      libraryService.expireKeepsInLibraries();
      var data = {
        'ids': _.pluck(keeps, 'id')
      };
      return net.removeManyKeepsFromLibrary(libraryId, data);
    }

    function removeKeepImage(libraryId, keepId) {
      return net.changeKeepImage(libraryId, keepId, { image: null });
    }

    var api = {
      keepToLibrary: keepToLibrary,
      copyToLibrary: copyToLibrary,
      moveToLibrary: moveToLibrary,
      editKeepTitle: editKeepTitle,
      fetchFullKeepInfo: fetchFullKeepInfo,
      getFullKeepInfo: getFullKeepInfo,
      unkeepFromLibrary: unkeepFromLibrary,
      unkeepManyFromLibrary: unkeepManyFromLibrary,
      removeKeepImage: removeKeepImage
    };

    return api;
  }
]);
