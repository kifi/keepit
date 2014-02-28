'use strict';

angular.module('kifi.keepService', [])

.factory('keepService', [
  '$http', 'env', '$q', '$timeout', '$document', '$rootScope',
  function ($http, env, $q, $timeout, $document, $rootScope) {

    var list = [],
      selected = {},
      before = null,
      end = false,
      previewed = null,
      limit = 30,
      isDetailOpen = false,
      singleKeepBeingPreviewed = false,
      previewUrls = {},
      doc = $document[0];

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
          $timeout(function () {
            api.prefetchImages(urls);
          });

          _.forEach(keeps, function (keep) {
            keep.screenshot = urls[keep.url];
          });
        });
      }
    }

    function processHit(hit) {
      _.extend(hit, hit.bookmark);

      hit.keepers = hit.users;
      hit.others = hit.count - hit.users.length - (hit.isMyBookmark && !hit.isPrivate ? 1 : 0);
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

      preview: function (keep) {
        if (keep == null) {
          singleKeepBeingPreviewed = false;
          isDetailOpen = false;
        }
        else {
          singleKeepBeingPreviewed = true;
          isDetailOpen = true;
        }
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
          return api.preview(null);
        }
        return api.preview(keep);
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
          }
          else if (countSelected === 1 && isDetailOpen === true) {
            api.preview(api.getFirstSelected());
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
        if (api.isSelected(keep)) {
          return api.unselect(keep);
        }
        return api.select(keep);
      },

      getFirstSelected: function () {
        var id = _.keys(selected)[0];
        if (!id) {
          return null;
        }

        for (var i = 0, l = list.length, keep; i < l; i++) {
          keep = list[i];
          if (keep.id === id) {
            return keep;
          }
        }

        return null;
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
        previewed = null;
        isDetailOpen = false;
        singleKeepBeingPreviewed = false;
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
        if (keep && keep.url) {
          var url = env.xhrBaseEliza + '/chatter';

          var data = {
            url: keep.url
          };

          return $http.post(url, data).then(function (res) {
            var data = res.data;
            keep.conversationCount = data.threads;
            return data;
          });
        }
        return $q.when({'threads': 0});
      },

      fetchScreenshotUrls: function (keeps) {
        if (keeps && keeps.length) {
          var url = env.xhrBase + '/keeps/screenshot';
          return $http.post(url, {
            urls: _.pluck(keeps, 'url')
          }).then(function (res) {
            return res.data.urls;
          });
        }
        return $q.when(keeps || []);
      },

      prefetchImages: function (urls) {
        _.forEach(urls, function (imgUrl, key) {
          if (!(key in previewUrls)) {
            previewUrls[key] = imgUrl;
            doc.createElement('img').src = imgUrl;
          }
        });
      },

      keep: function (keeps, isPrivate) {
        if (!(keeps && keeps.length)) {
          return $q.when(keeps || []);
        }

        isPrivate = !! isPrivate;

        var url = env.xhrBase + '/keeps/add';
        return $http.post(url, {
          keeps: keeps.map(function (keep) {
            return {
              title: keep.title,
              url: keep.url,
              isPrivate: isPrivate
            };
          })
        }).then(function () {
          _.forEach(keeps, function (keep) {
            keep.isMyBookmark = true;
            keep.isPrivate = isPrivate;
          });
          return keeps;
        });
      },

      unkeep: function (keeps) {
        if (!(keeps && keeps.length)) {
          return $q.when(keeps || []);
        }

        var url = env.xhrBase + '/keeps/remove';
        return $http.post(url, _.map(keeps, function (keep) {
          return {
            url: keep.url
          };
        })).then(function () {
          var map = _.reduce(keeps, function (map, keep) {
            map[keep.id] = true;
            return map;
          }, {});

          _.remove(list, function (keep) {
            return map[keep.id];
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

        var url = env.xhrBaseSearch;
        return $http.get(url, {
          params: {
            q: query || void 0,
            f: filter || 'm',
            maxHits: 30,
            context: context || void 0
          }
        }).then(function (res) {
          var data = res.data,
            hits = data.hits || [];

          if (!data.mayHaveMore) {
            end = true;
          }

          hits.forEach(processHit);

          list.push.apply(list, hits);

          fetchScreenshots(hits);

          return data;
        });
      }
    };

    return api;
  }
]);
