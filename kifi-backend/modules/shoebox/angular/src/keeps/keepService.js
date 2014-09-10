'use strict';

angular.module('kifi')

.factory('keepService', [
  '$http', 'env', '$q', '$timeout', '$document', '$rootScope', 'undoService', '$log', 'Clutch', '$analytics', 'routeService', '$location', 'tagService', 'util',
  function ($http, env, $q, $timeout, $document, $rootScope, undoService, $log, Clutch, $analytics, routeService, $location, tagService, util) {

    var list = [],
      before = null,
      end = false,
      limit = 10,
      smallLimit = 4,
      previewUrls = {},
      doc = $document[0],
      seqReset = 0,
      seqResult = 0;

    $rootScope.$on('tags.remove', function (tagId) {
      var keeps = _.filter(list, function (keep) {
        return keep.removeTag(tagId);
      });
      tagService.setRemovedKeepsForTag(tagId, keeps);
    });

    $rootScope.$on('tags.unremove', function (tagId) {
      _.forEach(tagService.getRemovedKeepsForTag(tagId), function (keep) {
        keep.addTag(tagId);
      });
    });

    function expiredConversationCount(keep) {
      if (!keep.conversationUpdatedAt) {
        return true;
      }
      var diff = new Date().getTime() - keep.conversationUpdatedAt.getTime();
      return diff / 1000 > 15; // conversation count is older than 15 seconds
    }

    function uploadBookmarkFile(file, makePublic) {
      var deferred = $q.defer();
      if (file) {
        var xhr = new XMLHttpRequest();
        xhr.withCredentials = true;
        xhr.upload.addEventListener('progress', function (e) {
          deferred.notify({'name': 'progress', 'event': e});
        });
        xhr.addEventListener('load', function () {
          deferred.resolve(JSON.parse(xhr.responseText));
        });
        xhr.addEventListener('error', function (e) {
          deferred.reject(e);
        });
        xhr.addEventListener('loadend', function (e) {
          deferred.notify({'name': 'loadend', 'event': e});
        });
        xhr.open('POST', routeService.uploadBookmarkFile(makePublic), true);
        xhr.send(file);
      } else {
        deferred.reject({'error': 'no file'});
      }
      return deferred.promise;
    }

    function buildKeep(keep, isMyBookmark) {
      keep.isMyBookmark = isMyBookmark;
      if (typeof keep.isMyBookmark !== 'boolean') {
        keep.isMyBookmark = true;
      }
      keep.tagList = keep.tagList || [];
      keep.collections = keep.collections || [];

      keep.addTag = function (tag) {
        this.tagList.push(tag);
        this.collections.push(tag.id);
      };

      keep.removeTag = function (tagId) {
        var idx1 = _.findIndex(this.tagList, function (tag) {
          return tag.id === tagId;
        });
        if (idx1 > -1) {
          this.tagList.splice(idx1, 1);
        }
        var idx2 = this.collections.indexOf(tagId);
        if (idx2 > -1) {
          this.collections.splice(idx2, 1);
          return true;
        } else {
          return false;
        }
      };
    }

    var keepList = new Clutch(function (url, config) {
      $log.log('keepService.getList()', config && config.params);

      return $http.get(url, config).then(function (res) {
        var data = res.data,
          keeps = data.keeps || [];

        _.forEach(keeps, buildKeep);

        return { keeps: keeps, before: data.before };
      });
    });

    function processKeepAction(data, existingKeeps) {
      $log.log('keepService.keep()', data);

      var url = env.xhrBase + '/keeps/add';
      var config = {
        params: { separateExisting: true }
      };
      return $http.post(url, data, config).then(function (res) {
        var keeps = (existingKeeps || []).concat(res.data.keeps || []);
        keeps = _.uniq(keeps, function (keep) {
          // todo(martin): we should have the backend return the external id
          // this way we could compare ids instead of urls
          return keep.url;
        });
        _.forEach(keeps, buildKeep);
        $analytics.eventTrack('user_clicked_page', {
          'action': 'keep',
          'path': $location.path()
        });
        tagService.addToKeepCount(res.data.keeps.length);
        prependKeeps(keeps);
        return res.data;
      });
    }

    function getSingleKeep(keepId) {
      var url = routeService.getKeep(keepId);
      var config = {
        params: { withFullInfo: true }
      };

      return $http.get(url, config).then(function (result) {
        var keep = result.data;
        end = true;
        list.length = 0;
        buildKeep(keep);
        appendKeeps([keep]);
        return keep;
      });
    }

    function fetchFullKeepInfo(keep) {
      keep.isLoading = true;
      var url = routeService.getKeep(keep.id);
      var config = {
        params: { withFullInfo: true }
      };

      return $http.get(url, config).then(function (result) {
        util.completeObjectInPlace(keep, result.data);
        buildKeep(keep);
        keep.isLoading = false;
        return keep;
      });
    }

    function insertKeeps(keeps, insertFn) {
      // Check which keeps are already in the list
      var existing = [];
      var nonExisting = [];
      keeps.forEach(function (keep) {
        var existingKeep = _.find(list, function (existingKeep) {
          // todo(martin): we should have the backend return the external id
          // this way we could compare ids instead of urls
          return keep.url === existingKeep.url;
        });
        if (existingKeep) {
          // todo(martin) should we update the existingKeep info?
          existing.push(existingKeep);
        } else {
          nonExisting.push(keep);
        }
      });
      insertFn.apply(list, nonExisting);
      seqResult++;
      existing.forEach(makeKept);
      before = list.length ? list[list.length - 1].id : null;

    }

    function prependKeeps(keeps) {
      insertKeeps(keeps, list.unshift);
    }

    function appendKeeps(keeps) {
      insertKeeps(keeps, list.push);
    }

    function sanitizeUrl(url) {
      var regex = /^[a-zA-Z]+:\/\//;
      if (!regex.test(url)) {
        return 'http://' + url;
      } else {
        return url;
      }
    }

    function makeKept(keep) {
      keep.unkept = false;
      keep.isMyBookmark = true;
      if (keep.tagList) {
        keep.tagList.forEach(function (tag) {
          var existingTag = tagService.getById(tag.id);
          if (existingTag) {
            existingTag.keeps++;
          }
        });
      }
    }

    function makeUnkept(keep) {
      keep.unkept = true;
      keep.isMyBookmark = false;
      if (keep.tagList) {
        keep.tagList.forEach(function (tag) {
          var existingTag = tagService.getById(tag.id);
          if (existingTag) {
            existingTag.keeps--;
          }
        });
      }
    }

    var api = {
      list: list,

      seqReset: function () {
        return seqReset;
      },

      seqResult: function () {
        return seqResult;
      },

      buildKeep: buildKeep,

      reset: function () {
        $log.log('keepService.reset()');
        before = null;
        end = false;
        list.length = 0;
        keepList.expireAll();
        seqReset++;
      },

      getList: function (params) {
        if (end) {
          return $q.when([]);
        }

        var url = env.xhrBase + '/keeps/all';
        params = params || {};
        if (before) {
          params.count = params.count || limit;
        } else {
          params.count = smallLimit;
        }

        params.before = before || void 0;
        params.withPageInfo = true;

        var config = {
          params: params
        };

        return keepList.get(url, config).then(function (result) {

          var keeps = result.keeps;
          var _before = result.before;

          if (!keeps.length || keeps.length < params.count - 1) {
            end = true;
          }

          if (!_before) {
            list.length = 0;
          }

          appendKeeps(keeps);
          return keeps;
        });
      },

      joinTags: function (keeps, tags) {
        var idMap = _.reduce(tags, function (map, tag) {
          if (tag && tag.id) {
            map[tag.id] = tag;
          }
          return map;
        }, {});

        _.forEach(keeps, function (keep) {
          var newTagList = _.map(_.union(keep.collections, keep.tags), function (tagId) {
            return idMap[tagId] || null;
          }).filter(function (tag) {
            return tag != null;
          });
          keep.tagList = util.replaceArrayInPlace(keep.tagList, newTagList);
        });
      },

      getChatter: function (keep) {
        if (keep && keep.url && expiredConversationCount(keep)) {
          var url = env.xhrBaseEliza + '/chatter';

          var data = {
            url: keep.url
          };

          $log.log('keepService.getChatter()', data);

          return $http.post(url, data).then(function (res) {
            var resp = res.data;
            keep.conversationCount = resp.threads;
            keep.conversationUpdatedAt = new Date();
            return resp;
          });
        }
        return $q.when({'threads': 0});
      },

      prefetchImages: function (urls) {
        _.forEach(urls, function (imgUrl, key) {
          if (!(key in previewUrls) && imgUrl) {
            previewUrls[key] = imgUrl;
            doc.createElement('img').src = imgUrl;
          }
        });
      },

      validateUrl: function (keepUrl) {
        // Extremely simple for now, can be developed in the future
        return keepUrl.indexOf('.') !== -1;
      },

      keepUrl: function (keepUrls, isPrivate) {
        var data = {
          keeps: keepUrls.map(function (keepUrl) {
            return {
              url: sanitizeUrl(keepUrl),
              isPrivate: !!isPrivate
            };
          })
        };

        return processKeepAction(data);
      },

      keep: function (keeps, isPrivate) {
        if (!(keeps && keeps.length)) {
          return $q.when(keeps || []);
        }

        // true if we should override the current keeps' privacy settings
        var keepPrivacy = isPrivate == null;

        var data = {
          keeps: keeps.map(function (keep) {
            if (!keepPrivacy) {
              // updated before the actual call
              keep.isPrivate = !!isPrivate;
            }
            return {
              title: keep.title,
              url: keep.url,
              isPrivate: !!keep.isPrivate
            };
          })
        };

        var result = processKeepAction(data, keeps);
        return result.keeps;
      },

      unkeep: function (keeps) {
        if (!(keeps && keeps.length)) {
          return $q.when(keeps || []);
        }

        var url, data;

        if (keeps.length === 1 && keeps[0].id) {
          url = routeService.removeSingleKeep(keeps[0].id);
          data = {};
        } else {
          url = routeService.removeKeeps;
          data = _.map(keeps, function (keep) {
            return {
              url: keep.url
            };
          });
        }

        $log.log('keepService.unkeep()', url, data);

        return $http.post(url, data).then(function () {
          _.forEach(keeps, function (keep) {
            makeUnkept(keep);
            if (api.isSelected(keep)) {
              api.unselect(keep);
            }
          });

          var message = keeps.length > 1 ? keeps.length + ' Keeps deleted.' : 'Keep deleted.';
          if (keeps.length > 1 || keeps.some(function (keep) { return !keep.isProtected; })) {
            // if it is only one unprotected keep, it will likely still be shown
            // on screen with a "Keep" button, so no real need to use the undo service.
            undoService.add(message, function () {
              api.keep(keeps);
            });
          }
          tagService.addToKeepCount(-1 * keeps.length);

          $analytics.eventTrack('user_clicked_page', {
            'action': 'unkeep',
            'path': $location.path()
          });
          return keeps;
        });
      },

      togglePrivate: function (keeps) {
        return api.keep(keeps, !_.every(keeps, 'isPrivate'));
      },

      getSingleKeep: getSingleKeep,

      fetchFullKeepInfo: fetchFullKeepInfo,

      isEnd: function () {
        return !!end;
      },

      getSubtitle: function (mouseover) {
        var selectedCount = api.getSelectedLength(),
          numShown = list.length;

        if (mouseover) {
          if (selectedCount === numShown) {
            return 'Deselect all ' + numShown + ' Keeps below';
          }
          return 'Select all ' + numShown + ' Keeps below';
        }

        switch (selectedCount) {
        case 0:
          return null;
        case 1:
          return selectedCount + ' Keep selected';
        default:
          return selectedCount + ' Keeps selected';
        }
      },

      uploadBookmarkFile: uploadBookmarkFile,

      getKeepsByTagId: function (tagId, params) {
        params = params || {};
        params.collection = tagId;
        return api.getList(params);
      },

      getKeepsByHelpRank: function(helprank, params) {
        params = params || {};
        params.helprank = helprank;
        return api.getList(params);
      }
    };

    return api;
  }
]);
