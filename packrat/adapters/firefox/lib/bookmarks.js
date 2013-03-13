// see https://developer.mozilla.org/en-US/docs/Places_Developer_Guide

var { Cc, Ci } = require('chrome')
var bookmarks = Cc["@mozilla.org/browser/nav-bookmarks-service;1"].getService(Ci.nsINavBookmarksService);
var history = Cc["@mozilla.org/browser/nav-history-service;1"].getService(Ci.nsINavHistoryService);

function newURI(url) {
  return Cc["@mozilla.org/network/io-service;1"].getService(Ci.nsIIOService).newURI(url, null, null);
}

function runQuery(query) {
  var options = history.getNewQueryOptions();
  options.queryType = options.QUERY_TYPE_BOOKMARKS;

  var result = history.executeQuery(query, options).root, resultArray = [];
  result.containerOpen = true;
  for (let i = 0, n = result.childCount; i < n; ++i) {
    let node = result.getChild(i);
    resultArray.push({id: node.itemId, title: node.title, url: node.uri});
  }
  return resultArray;
}

exports.create = function(parentId, name, url, callback) {  // TODO: not working?
  var id = bookmarks.insertBookmark(parentId, newURI(url), bookmarks.DEFAULT_INDEX, name);
  callback({id: id, url: url, title: name});
};

exports.createFolder = function(parentId, name, callback) {
  var id = bookmarks.createFolder(parentId, name, bookmarks.DEFAULT_INDEX);
  callback({id: id, title: name});
};

exports.get = function(id, callback) {
  switch (bookmarks.getItemType(id)) {
  case bookmarks.TYPE_BOOKMARK:
    callback({id: id, title: bookmarks.getItemTitle(id), url: bookmarks.getBookmarkURI(id)});
    break;
  case bookmarks.TYPE_FOLDER:
    callback({id: id, title: bookmarks.getItemTitle(id)});
    break;
  default:
    callback(null);
  }
};

exports.getAll = function(callback) {
  callback(runQuery(history.getNewQuery()).filter(function(b) {return /^https?:/.test(b.url)}));
};

exports.getBarFolder = function(callback) {
  callback({id: bookmarks.toolbarFolder});
};

exports.getChildren = function(id, callback) {
  var query = history.getNewQuery();
  query.setFolders([id], 1);
  callback(runQuery(query));
};

exports.move = function(id, newParentId) {
  bookmarks.moveItem(id, newParentId, bookmarks.DEFAULT_INDEX);
};

exports.remove = function(id) {
  bookmarks.removeItem(id);
};

exports.search = function(url, callback) {
  var ids = bookmarks.getBookmarkIdsForURI(newURI(url));
  callback(ids.map(function(id) {
    return {id: id, title: bookmarks.getItemTitle(id), url: bookmarks.getBookmarkURI(id)};
  }));
};
