'use strict';

angular.module('kifi')

.factory('tagService', [
  '$http', 'env', '$q', '$rootScope', 'undoService', 'routeService', '$analytics', '$location', 'util',
  function ($http, env, $q, $rootScope, undoService, routeService, $analytics, $location, util) {
    var allTags = [],
      list = [],
      tagsById = {},
      prevFilter = '',
      fetchAllPromise = null,
      removedKeepsByTagId = {};

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
      var post = $http.post(url, payload).then(function (res) {
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
      $analytics.eventTrack('user_clicked_page', {
        'action': 'addKeepsToTag',
        'path': $location.path()
      });
      return post;
    }

    function persistTagOrdering(id, newInd) {
      var payload = {
        tagId: id,
        newIndex: newInd
      };
      $http.post(routeService.reorderTag, payload).then(function () {});
      api.fetchAll();
    }

    function reorderTag(srcTag, index) {
      var oldIndex = _.indexOf(allTags, srcTag);
      if (oldIndex !== -1) {
        if (index < oldIndex) {
          // if moving the tag above its old location, the oldIndex needs to
          // be incremented because a copy of this tag is being added in front
          // of the original old tag
          oldIndex += 1;
        }
        allTags.splice(index, 0, srcTag); // add to array
        allTags.splice(oldIndex, 1);      // remove from array
        api.refreshList();
        var currentIndex = _.indexOf(allTags, srcTag);
        persistTagOrdering(srcTag.id, currentIndex);
      }
      $analytics.eventTrack('user_clicked_page', {
        'action': 'reorderTag',
        'path': $location.path()
      });
    }

    var options = {
      keys: ['name'],
      threshold: 0.3
    };
    var fuseSearch = new Fuse(allTags, options);

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
        var newList = allTags;
        if (term.length) {
          newList = fuseSearch.search(term);
        }

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
          list.push.apply(list, allTags);

          allTags.forEach(function (tag) {
            tag.lowerName = tag.name.toLowerCase();
            tagsById[tag.id] = tag;
          });
          api.refreshList();

          return allTags;
        });

        return fetchAllPromise.then(function () {
          return list;
        });
      },

      create: function (name) {
        var url = env.xhrBase + '/collections/create';
        var post = $http.post(url, {'name': name}).then(function (res) {
          var tag = res.data;

          tag.keeps = tag.keeps || 0;
          tag.isNew = true;
          tag.lowerName = tag.name.toLowerCase();
          tagsById[tag.id] = tag;
          allTags.unshift(tag);
          api.refreshList();

          api.filterList('');
          return tag;
        });
        $analytics.eventTrack('user_clicked_page', {
          'action': 'createTag',
          'path': $location.path()
        });
        return post;
      },

      remove: function (tag) {
        var url = env.xhrBase + '/collections/' + tag.id + '/delete';
        var post = $http.post(url).then(function () {
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
          undoService.add('Tag deleted.', function () {
            api.unremove(tag, allIndex);
          });
          return tag;
        });
        $analytics.eventTrack('user_clicked_page', {
          'action': 'removeTag',
          'path': $location.path()
        });
        return post;
      },

      unremove: function (tag, index) {
        var url = env.xhrBase + '/collections/' + tag.id + '/undelete';
        var post = $http.post(url).then(function () {
          if (index !== -1) {
            allTags.splice(index, 0, tag);
            api.refreshList();
          }
          $rootScope.$emit('tags.unremove', tag.id);
          $rootScope.$emit('undoRemoveTag');
          persistTagOrdering(tag.id, index);
          return tag;
        });
        $analytics.eventTrack('user_clicked_page', {
          'action': 'unremoveTag',
          'path': $location.path()
        });
        return post;
      },

      getRemovedKeepsForTag: function (tagId) {
        return removedKeepsByTagId[tagId];
      },

      setRemovedKeepsForTag: function (tagId, keeps) {
        removedKeepsByTagId[tagId] = keeps;
      },

      removeKeepsFromTag: function (tagId, keeps) {
        var url = env.xhrBase + '/collections/' + tagId + '/removeKeeps';
        var post = $http.post(url, _.pluck(keeps, 'id')).then(function (res) {
          updateKeepCount(tagId, -keeps.length);
          keeps.forEach(function (keep) {
            keep.removeTag(tagId);
          });
          return res;
        });
        $analytics.eventTrack('user_clicked_page', {
          'action': 'removeKeepsFromTag',
          'path': $location.path()
        });
        return post;
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
