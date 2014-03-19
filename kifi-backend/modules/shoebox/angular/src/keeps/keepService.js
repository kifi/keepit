'use strict';

angular.module('kifi.keepService', ['kifi.undo'])

.factory('keepService', [
  '$http', 'env', '$q', '$timeout', '$document', '$rootScope', 'undoService', '$log',
  function ($http, env, $q, $timeout, $document, $rootScope, undoService, $log) {

    var list = [],
      selected = {},
      before = null,
      end = false,
      previewed = null,
      selectedIdx,
      limit = 30,
      isDetailOpen = false,
      singleKeepBeingPreviewed = false,
      previewUrls = {},
      doc = $document[0],
      screenshotDebouncePromise = false;

    $rootScope.$on('tags.remove', function (tagId) {
      _.forEach(list, function (keep) {
        if (keep.tagList) {
          keep.tagList = keep.tagList.filter(function (tag) {
            return tag.id !== tagId;
          });
        }
      });
    });

    $rootScope.$on('tags.removeFromKeep', function (e, data) {
      var tagId = data.tagId,
          keepId = data.keepId;
      _.forEach(list, function (keep) {
        if (keep.id === keepId && keep.tagList) {
          keep.tagList = keep.tagList.filter(function (tag) {
            return tag.id !== tagId;
          });
        }
      });
    });

    $rootScope.$on('tags.addToKeep', function (e, data) {
      var tag = data.tag,
          keepId = data.keep.id;
      _.forEach(list, function (keep) {
        if (keep.id === keepId && keep.tagList) {
          var isAlreadyThere = _.find(keep.tagList, function (existingTag) {
            return existingTag.id === tag.id;
          });
          if (!isAlreadyThere) {
            keep.tagList.push(tag);
          }
        }
      });
    });

    function fetchScreenshots(keeps) {
      if (keeps && keeps.length) {
        api.fetchScreenshotUrls(keeps).then(function (urls) {
          _.forEach(keeps, function (keep) {
            keep.screenshot = urls[keep.url];
          });
        });
      }
    }

    function lookupScreenshotUrls(keeps) {
      if (keeps && keeps.length) {
        var url = env.xhrBase + '/keeps/screenshot',
          data = {
            urls: _.pluck(keeps, 'url')
          };

        $log.log('keepService.lookupScreenshotUrls()', data);

        return $http.post(url, data).then(function (res) {
          $timeout(function () {
            api.prefetchImages(res.data.urls);
          });
          return res.data.urls;
        });
      }
      return $q.when(keeps || []);
    }

    function processHit(hit) {
      _.extend(hit, hit.bookmark);

      hit.keepers = hit.users;
      hit.others = hit.count - hit.users.length - (hit.isMyBookmark && !hit.isPrivate ? 1 : 0);
    }

    function keepIdx(keep) {
      if (!keep) {
        return -1;
      }
      var givenId = keep.id;
      for (var i = 0, l = list.length; i < l; i++) {
        if (list[i].id === givenId) {
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
      return diff / 1000 > 10; // conversation count is older than 5 seconds
    }


    var api = {
      list: list,

      totalKeepCount: 0,

      isDetailOpen: function () {
        return isDetailOpen;
      },

      isSingleKeep: function () {
        return singleKeepBeingPreviewed;
      },

      getPreviewed: function () {
        return previewed || null;
      },

      isPreviewed: function (keep) {
        return !!previewed && previewed === keep;
      },

      getHighlighted: function () {
        return list[selectedIdx];
      },

      preview: function (keep) {
        if (keep == null) {
          api.clearState();
        }
        else {
          singleKeepBeingPreviewed = true;
          isDetailOpen = true;
        }
        selectedIdx = keepIdx(keep);
        previewed = keep;
        api.getChatter(previewed);

        return keep;
      },

      togglePreview: function (keep) {
        if (api.isPreviewed(keep) && _.size(selected) > 1) {
          previewed = null;
          isDetailOpen = true;
          singleKeepBeingPreviewed = false;
          return null;
        }
        else if (api.isPreviewed(keep)) {
          return api.clearState();
        }
        return api.preview(keep);
      },

      previewNext: function () {
        var previewedIdx = selectedIdx;
        var toPreview;
        if (list.length - 1 > previewedIdx) {
          toPreview = list[previewedIdx + 1];
          selectedIdx++;
        } else {
          toPreview = list[0];
          selectedIdx = 0;
        }
        api.togglePreview(toPreview);
      },

      previewPrev: function () {
        var previewedIdx = selectedIdx;
        var toPreview;
        if (previewedIdx > 0) {
          toPreview = list[previewedIdx - 1];
          selectedIdx--;
        } else {
          toPreview = list[0];
          selectedIdx = 0;
        }
        api.togglePreview(toPreview);
      },

      isSelected: function (keep) {
        return keep && keep.id && !!selected[keep.id];
      },

      select: function (keep) {
        var id = keep.id;
        if (id) {
          isDetailOpen = true;
          selected[id] = keep;
          if (_.size(selected) === 1) {
            api.preview(keep);
          }
          else {
            previewed = null;
            singleKeepBeingPreviewed = false;
          }
          selectedIdx = keepIdx(keep);
          return true;
        }
        return false;
      },

      unselect: function (keep) {
        var id = keep.id;
        if (id) {
          delete selected[id];
          var countSelected = _.size(selected);
          if (countSelected === 0 && isDetailOpen === true) {
            api.preview(keep);
            selectedIdx = keepIdx(keep);
          }
          else if (countSelected === 1 && isDetailOpen === true) {
            var first = api.getFirstSelected();
            selectedIdx = keepIdx(first);
            api.preview(first);
          }
          else {
            previewed = null;
            singleKeepBeingPreviewed = false;
          }
          return true;
        }
        return false;
      },

      toggleSelect: function (keep) {
        if (keep === undefined) {
          if (previewed) {
            return api.toggleSelect(previewed);
          } else if (selectedIdx >= 0) {
            return api.toggleSelect(list[selectedIdx]);
          }
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
        if (list.length === 0) {
          api.clearState();
        }
        else if (list.length === 1) {
          api.preview(list[0]);
        }
        else {
          previewed = null;
          isDetailOpen = true;
          singleKeepBeingPreviewed = false;
        }
      },

      unselectAll: function () {
        selected = {};
        api.clearState();
      },

      clearState: function () {
        isDetailOpen = false;
        $timeout(function () {
          if (isDetailOpen === false) {
            previewed = null;
            singleKeepBeingPreviewed = false;
          }
        }, 400);
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

        before = null;
        end = false;
        list.length = 0;
        selected = {};
        api.unselectAll();
      },

      getList: function (params) {
        if (end) {
          return $q.when([]);
        }

        var url = env.xhrBase + '/keeps/all';
        params = params || {};
        params.count = params.count || limit;
        params.before = before || void 0;

        var config = {
          params: params
        };

        $log.log('keepService.getList()', params);

        return $http.get(url, config).then(function (res) {
          var data = res.data,
            keeps = data.keeps || [];
          if (!keeps.length) {
            end = true;
          }

          if (!data.before) {
            list.length = 0;
          }

          list.push.apply(list, keeps);
          before = list.length ? list[list.length - 1].id : null;

          _.forEach(keeps, function (keep) {
            keep.isMyBookmark = true;
          });

          fetchScreenshots(keeps);

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
          keep.tagList = _.map(keep.collections || keep.tags, function (tagId) {
            return idMap[tagId] || null;
          }).filter(function (tag) {
            return tag != null;
          });
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
            var data = res.data;
            keep.conversationCount = data.threads;
            keep.conversationUpdatedAt = new Date();
            return data;
          });
        }
        return $q.when({'threads': 0});
      },

      fetchScreenshotUrls: function (urls) {
        var previousCancelled = screenshotDebouncePromise && $timeout.cancel(screenshotDebouncePromise);

        if (previousCancelled) {
          // We cancelled an existing call that was in a timeout. User is likely typing actively.
          screenshotDebouncePromise = $timeout(angular.noop, 1000);
          return screenshotDebouncePromise.then(function () {
            return lookupScreenshotUrls(urls);
          });
        }

        // No previous request was going. Start a timer, but go ahead and run the screenshot lookup.
        screenshotDebouncePromise = $timeout(angular.noop, 1000);
        return lookupScreenshotUrls(urls);
      },

      prefetchImages: function (urls) {
        _.forEach(urls, function (imgUrl, key) {
          if (!(key in previewUrls) && imgUrl) {
            previewUrls[key] = imgUrl;
            doc.createElement('img').src = imgUrl;
          }
        });
      },

      keep: function (keeps, isPrivate) {
        if (!(keeps && keeps.length)) {
          return $q.when(keeps || []);
        }

        var keepPrivacy = isPrivate == null;
        isPrivate = !! isPrivate;

        var url = env.xhrBase + '/keeps/add',
          data = {
            keeps: keeps.map(function (keep) {
              return {
                title: keep.title,
                url: keep.url,
                isPrivate: keepPrivacy ? !! keep.isPrivate : isPrivate
              };
            })
          };

        $log.log('keepService.keep()', data);

        return $http.post(url, data).then(function () {
          _.forEach(keeps, function (keep) {
            keep.isMyBookmark = true;
            keep.isPrivate = keepPrivacy ? !! keep.isPrivate : isPrivate;
            keep.unkept = false;
          });
          return keeps;
        });
      },

      unkeep: function (keeps) {
        if (!(keeps && keeps.length)) {
          return $q.when(keeps || []);
        }

        var url = env.xhrBase + '/keeps/remove',
            data = _.map(keeps, function (keep) {
              return {
                url: keep.url
              };
            });

        $log.log('keepService.unkeep()', data);

        return $http.post(url, data).then(function () {
          /*
          var map = _.reduce(keeps, function (map, keep) {
            map[keep.id] = true;
            return map;
          }, {});

          _.remove(list, function (keep) {
            return map[keep.id];
          });
          */
          _.forEach(keeps, function (keep) {
            keep.unkept = true;
            if (previewed === keep) {
              api.togglePreview(keep);
            }
            if (api.isSelected(keep)) {
              api.unselect(keep);
            }
          });

          var message = keeps.length > 1 ? keeps.length + ' Keeps deleted.' : 'Keep deleted.';
          undoService.add(message, function () {
            api.keep(keeps);
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

      find: function (query, filter, context) {
        if (end) {
          return $q.when([]);
        }

        var url = env.xhrBaseSearch,
          data = {
            params: {
              q: query || void 0,
              f: filter || 'm',
              maxHits: 30,
              context: context || void 0
            }
          };

        $log.log('keepService.find()', data);

        return $http.get(url, data).then(function (res) {
          var data = res.data,
            hits = data.hits || [];

          if (!data.mayHaveMore) {
            end = true;
          }

          _.forEach(hits, processHit);

          list.push.apply(list, hits);

          fetchScreenshots(hits);

          return data;
        });
      },

      getKeepsByTagId: function (tagId, params) {
        params = params || {};
        params.collection = tagId;
        return api.getList(params);
      }
    };

    return api;
  }
]);
