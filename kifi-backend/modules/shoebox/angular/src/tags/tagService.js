'use strict';

angular.module('kifi.tagService', [
  'kifi.undo',
  'kifi.routeService',
  'angulartics'
])

.factory('tagService', [
  '$http', 'env', '$q', '$rootScope', 'undoService', 'routeService', '$analytics', 'util',
  function ($http, env, $q, $rootScope, undoService, routeService, $analytics, util) {
    var allTags = [],
      list = [],
      tagsById = {},
      prevFilter = '',
      fetchAllPromise = null,
      removedKeepsByTagId = {},
      totalKeepCount; // this should get out of this service

    var listLength = 70;

    function indexById(array, id) {
      for (var i = 0, l = array.length; i < l; i++) {
        if (array[i].id === id) {
          return i;
        }
      }
      return -1;
    }

    function updateKeepCount(id, delta) {
      var index = indexById(allTags, id);
      if (index !== -1) {
        var tag = allTags[index];
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
      return $http.post(url, payload).then(function (res) {
        $analytics.eventTrack('user_clicked_page', {
          'action': 'addKeepsToTag'
        });
        if (res.data && res.data.addedToCollection) {
          keeps.forEach(function (keep) {
            if (!_.contains(_.pluck(keep.tagList, 'id'), tag.id)) {
              keep.addTag(tag);
            }
          });
          updateKeepCount(tag.id, res.data.addedToCollection);
        }
        return res;
      });
    }

    function persistOrdering() {
      $http.post(routeService.tagOrdering, _.pluck(allTags, 'id')).then(function () {
      });
      api.fetchAll();
    }

    function reorderTag(srcTag, index) {
      var newSrcTag = _.clone(srcTag);
      var srcTagId = srcTag.id;
      newSrcTag.id = -1;
      allTags.splice(index, 0, newSrcTag);
      _.remove(allTags, function (tag) { return tag.id === srcTagId; });
      api.refreshList();
      newSrcTag.id = srcTagId;
      persistOrdering();
      $analytics.eventTrack('user_clicked_page', {
        'action': 'reorderTag'
      });
    }

    var api = {
      allTags: allTags,

      list: list,

      getById: function (tagId) {
        return tagsById[tagId] || null;
      },

      promiseById: function (tagId) {
        return api.fetchAll().then(function () {
          return api.getById(tagId);
        });
      },

      filterList: function (term) {
        var lowerTerm = term.toLowerCase();
        var searchList = term.indexOf(prevFilter) === 0 && prevFilter.length > 0  && list.length < listLength ? list : allTags;

        var newList = [];

        for (var i = 0, ins = 0, sz = searchList.length; i < sz && ins < listLength; i++) {
          var tag = searchList[i];
          if (tag.lowerName.indexOf(lowerTerm) !== -1) {
            newList.push(tag);
            ins++;
          }
        }

        prevFilter = term;
        util.replaceArrayInPlace(list, newList);
        return list;
      },

      refreshList: function () {
        api.filterList(prevFilter);
      },

      fetchAll: function (force) {
        if (!force && fetchAllPromise) {
          return fetchAllPromise.then(function () {
            return list;
          });
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
          allTags.length = 0;
          allTags.push.apply(allTags, tags);
          list.length = 0;
          list.push.apply(list, allTags.slice(0, listLength));

          allTags.forEach(function (tag) {
            tag.lowerName = tag.name.toLowerCase();
            tagsById[tag.id] = tag;
          });
          api.refreshList();

          totalKeepCount = res.data.keeps;

          return allTags;
        });

        return fetchAllPromise.then(function () {
          return list;
        });
      },

      create: function (name) {
        var url = env.xhrBase + '/collections/create';
        var payload = {
          name: name
        };
        return $http.post(url, payload).then(function (res) {
          var tag = res.data;

          tag.keeps = tag.keeps || 0;
          tag.lowerName = tag.name.toLowerCase();
          tagsById[tag.id] = tag;
          allTags.unshift(tag);
          api.refreshList();

          api.filterList('');

          $analytics.eventTrack('user_clicked_page', {
            'action': 'createTag'
          });
          return tag;
        });
      },

      remove: function (tag) {
        var url = env.xhrBase + '/collections/' + tag.id + '/delete';
        return $http.post(url).then(function () {
          var allIndex = indexById(allTags, tag.id);
          if (allIndex !== -1) {
            allTags.splice(allIndex, 1);
            api.refreshList();
            var listIndex = indexById(list, tag.id);
            if (listIndex !== -1) {
              list.splice(listIndex, 1);
            }
          }
          $rootScope.$emit('tags.remove', tag.id);
          $analytics.eventTrack('user_clicked_page', {
            'action': 'removeTag'
          });
          undoService.add('Tag deleted.', function () {
            api.unremove(tag, allIndex);
          });
          return tag;
        });
      },

      unremove: function (tag, index) {
        var url = env.xhrBase + '/collections/' + tag.id + '/undelete';
        return $http.post(url).then(function () {
          if (index !== -1) {
            allTags.splice(index, 0, tag);
            api.refreshList();
          }
          $rootScope.$emit('tags.unremove', tag.id);
          $analytics.eventTrack('user_clicked_page', {
            'action': 'unremoveTag'
          });
          persistOrdering();
          return tag;
        });
      },

      getRemovedKeepsForTag: function (tagId) {
        return removedKeepsByTagId[tagId];
      },

      setRemovedKeepsForTag: function (tagId, keeps) {
        removedKeepsByTagId[tagId] = keeps;
      },

      getTotalKeepCount: function () {
        return totalKeepCount;
      },

      rename: function (tagId, name) {
        function renameTag(id, name) {
          var allIndex = indexById(allTags, id);
          if (allIndex !== -1) {
            var tag = allTags[allIndex];
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
          $analytics.eventTrack('user_clicked_page', {
            'action': 'renameTag'
          });
          return renameTag(tag.id, tag.name);
        });
      },

      removeKeepsFromTag: function (tagId, keeps) {
        var url = env.xhrBase + '/collections/' + tagId + '/removeKeeps';
        $http.post(url, _.pluck(keeps, 'id')).then(function (res) {
          updateKeepCount(tagId, -keeps.length);
          keeps.forEach(function (keep) {
            keep.removeTag(tagId);
          });
          $analytics.eventTrack('user_clicked_page', {
            'action': 'removeKeepsFromTag'
          });
          return res;
        });
      },

      addKeepsToTag: addKeepsToTag,

      addKeepToTag: function (tag, keep) {
        return addKeepsToTag(tag, [keep]);
      },

      reorderTag: reorderTag
    };

    return api;
  }
]);
