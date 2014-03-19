'use strict';

angular.module('kifi.tagService', ['kifi.keepService', 'kifi.routeService'])

.factory('tagService', [
  '$http', 'env', '$q', '$rootScope', 'keepService', 'routeService',
  function ($http, env, $q, $rootScope, keepService, routeService) {
    var list = [],
      tagsById = {},
      fetchAllPromise = null;

    function indexById(id) {
      for (var i = 0, l = list.length; i < l; i++) {
        if (list[i].id === id) {
          return i;
        }
      }
      return -1;
    }

    function updateKeepCount(id, delta) {
      var index = indexById(id);
      if (index !== -1) {
        var tag = list[index];
        tag.keeps = (tag.keeps || 0) + delta;
        return tag;
      }
      return null;
    }

    function addKeepsToTag(tag, keeps) {
      var url = env.xhrBase + '/keeps/add';
      var payload = {
        collectionId: tag.id,
        keeps: keeps
      };
      $http.post(url, payload).then(function (res) {
        if (res.data && res.data.addedToCollection) {
          updateKeepCount(tag.id, res.data.addedToCollection);
          // broadcast change to interested parties
          keeps.forEach(function (keep) {
            $rootScope.$emit('tags.addToKeep', {tag: tag, keep: keep});
          });
        }
        return res;
      });
    }

    function reorderTag(isTop, srcTag, dstTag) {
      // isTop indicates whether dstTag should be placed before or after srcTag
      var index = _.findIndex(list, function (tag) { return tag.id === dstTag.id; });
      var newSrcTag = _.clone(srcTag);
      var srcTagId = srcTag.id;
      newSrcTag.id = -1;
      if (!isTop) {
        index += 1;
      }
      list.splice(index, 0, newSrcTag);
      _.remove(list, function (tag) { return tag.id === srcTagId; });
      for (var i = 0; i < list.length; i++) {
        if (list[i].id === -1) {
          list[i].id = srcTagId;
        }
      }
      $http.post(routeService.tagOrdering, _.pluck(list, 'id'));
      api.fetchAll();
    }

    var api = {
      list: list,

      getById: function (tagId) {
        return tagsById[tagId] || null;
      },

      promiseById: function (tagId) {
        return api.fetchAll().then(function () {
          return api.getById(tagId);
        });
      },

      fetchAll: function (force) {
        if (!force && fetchAllPromise) {
          return fetchAllPromise;
        }

        var url = env.xhrBase + '/collections/all';
        var config = {
          params: {
            sort: 'user',
            _: Date.now().toString(36)
          }
        };

        fetchAllPromise = $http.get(url, config).then(function (res) {
          var tags = res.data && res.data.collections || [];
          list.length = 0;
          list.push.apply(list, tags);

          list.forEach(function (tag) {
            tagsById[tag.id] = tag;
          });

          keepService.totalKeepCount = res.data.keeps; // a bit weird...

          return list;
        });

        return fetchAllPromise;
      },

      create: function (name) {
        var url = env.xhrBase + '/collections/create';

        return $http.post(url, {
          name: name
        }).then(function (res) {
          var tag = res.data;
          tag.keeps = tag.keeps || 0;
          list.unshift(tag);
          return tag;
        });
      },

      remove: function (tagId) {
        function removeTag(id) {
          var index = indexById(id);
          if (index !== -1) {
            list.splice(index, 1);
          }
        }

        var url = env.xhrBase + '/collections/' + tagId + '/delete';
        return $http.post(url).then(function () {
          removeTag(tagId);
          $rootScope.$emit('tags.remove', tagId);
          return tagId;
        });
      },

      rename: function (tagId, name) {
        function renameTag(id, name) {
          var index = indexById(id);
          if (index !== -1) {
            var tag = list[index];
            tag.name = name;
            return tag;
          }
          return null;
        }

        var url = env.xhrBase + '/collections/' + tagId + '/update';
        return $http.post(url, {
          name: name
        }).then(function (res) {
          var tag = res.data;
          return renameTag(tag.id, tag.name);
        });
      },

      removeKeepsFromTag: function (tagId, keepIds) {
        var url = env.xhrBase + '/collections/' + tagId + '/removeKeeps';
        $http.post(url, keepIds).then(function (res) {
          updateKeepCount(tagId, -keepIds.length);
          // broadcast change to interested parties
          keepIds.forEach(function (keepId) {
            $rootScope.$emit('tags.removeFromKeep', {tagId: tagId, keepId: keepId});
          });
          return res;
        });
      },

      addKeepsToTag: addKeepsToTag,

      addKeepToTag: function (tag, keep) {
        addKeepsToTag(tag, [keep]);
      },

      reorderTag: reorderTag
    };

    return api;
  }
]);
