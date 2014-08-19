'use strict';

angular.module('kifi')

.factory('keepService', [
  '$http', 'env', '$q', '$timeout', '$document', '$rootScope', 'undoService', '$log', 'Clutch', '$analytics', 'routeService', '$location', 'tagService', 'util',
  function ($http, env, $q, $timeout, $document, $rootScope, undoService, $log, Clutch, $analytics, routeService, $location, tagService, util) {


    function createPageSession() {
      return Math.random().toString(16).slice(2);
    }

    var list = [],
      lastSearchContext = null,
      pageSession = createPageSession(),
      refinements = -1,
      selected = {},
      before = null,
      end = false,
      selectedIdx = 0,
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

    function processHit(hit) {
      _.extend(hit, hit.bookmark);

      hit.keepers = hit.users;
      hit.others = hit.count - hit.users.length - (hit.isMyBookmark && !hit.isPrivate ? 1 : 0);
      hit.summary = hit.uriSummary;
      hit.isProtected = !hit.isMyBookmark; // will not be hidden if user keeps then unkeeps
      buildKeep(hit, hit.isMyBookmark);
    }

    function keepIdx(keep) {
      if (!keep) {
        return -1;
      }
      var givenId = keep.id;

      for (var i = 0, l = list.length; i < l; i++) {
        if (givenId && list[i].id === givenId) {
          return i;
        } else if (!givenId && list[i] === keep) {
          // No id, do object comparison. todo: have a better way to track keeps when they have no ids.
          return i;
        }
      }
      return -1;
    }

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

      lastSearchContext: function () {
      return lastSearchContext;
      },

      getHighlighted: function () {
        return list[selectedIdx];
      },

      isSelected: function (keep) {
        return keep && keep.id && !!selected[keep.id];
      },

      select: function (keep) {
        var id = keep.id;
        if (id) {
          selected[id] = keep;
          selectedIdx = keepIdx(keep);
          return true;
        }
        return false;
      },

      unselect: function (keep) {
        var id = keep.id;
        if (id) {
          delete selected[id];
          selectedIdx = keepIdx(keep);
          return true;
        }
        return false;
      },

      toggleSelect: function (keep) {
        if (keep === undefined) {
          return api.toggleSelect(list[selectedIdx]);
        } else if (api.isSelected(keep)) {
          return api.unselect(keep);
        } else if (keep) {
          return api.select(keep);
        }
      },

      getFirstSelected: function () {
        return _.values(selected)[0];
      },

      getSelectedLength: function () {
        return _.keys(selected).length;
      },

      getSelected: function () {
        return list.filter(function (keep) {
          return keep.id in selected;
        });
      },

      selectAll: function () {
        selected = _.reduce(list, function (map, keep) {
          map[keep.id] = true;
          return map;
        }, {});
      },

      unselectAll: function () {
        selected = {};
      },

      isSelectedAll: function () {
        return list.length && list.length === api.getSelectedLength();
      },

      toggleSelectAll: function () {
        if (api.isSelectedAll()) {
          return api.unselectAll();
        }
        return api.selectAll();
      },

      reset: function () {
        $log.log('keepService.reset()');
        pageSession = createPageSession();
        lastSearchContext = null;
        refinements = -1;
        before = null;
        end = false;
        list.length = 0;
        selected = {};
        api.unselectAll();
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

      toggleKeep: function (keeps, isPrivate) {
        var isKept = _.every(keeps, 'isMyBookmark');
        isPrivate = isPrivate == null ? _.some(keeps, 'isPrivate') : !! isPrivate;

        if (isKept) {
          return api.unkeep(keeps);
        }
        return api.keep(keeps, isPrivate);
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

      find: function (query, filter, context) {
        if (end) {
          return $q.when([]);
        }

        var url = routeService.search,
          reqData = {
            params: {
              q: query || void 0,
              f: filter || 'm',
              maxHits: 30,
              context: context || void 0,
              withUriSummary: true
            }
          };

        $log.log('keepService.find() req', reqData);

        return $http.get(url, reqData).then(function (res) {
          var resData = res.data,
            hits = resData.hits || [];

          $log.log('keepService.find() res', resData);
          if (!resData.mayHaveMore) {
            end = true;
          }

          $analytics.eventTrack('user_clicked_page', {
            'action': 'searchKifi',
            'hits': hits.size,
            'mayHaveMore': resData.mayHaveMore,
            'path': $location.path()
          });

          _.forEach(hits, processHit);
          appendKeeps(hits);

          refinements++;
          lastSearchContext = {
            origin: $location.origin,
            uuid: res.data.uuid,
            experimentId: res.data.experimentId,
            query: reqData.params.q,
            filter: reqData.params.f,
            maxResults: reqData.params.maxHits,
            kifiTime: null,
            kifiShownTime: null,
            kifiResultsClicked: null,
            refinements: refinements,
            pageSession: pageSession
          };
          return resData;
        });
      },

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
