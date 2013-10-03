  /**
   * Makes a request to the server to create a tag for a user.
   *
   * CREATE
   *   Request URL: https://api.kifi.com/site/collections/create
   *   Request Method: POST
   *   Request Payload: {"name":"hello"}
   *   Response: {
   *     "id":"dc76ee74-a141-4e96-a65f-e5ca58ddfe04",
   *     "name":"hello"
   *   }
   */
  create_tag: function(data, _, tab) {
    if (typeof data === 'string') {
      data = {
        name: data
      };
    }
    log("[create_tag]", data)();
    ajax("POST", "https://api.kifi.com/site/collections/create", data, function(o) {
      log("[create_tag] response:", o)();
      data.success = true;
      pageData[tab.nUri].tabs.forEach(function(tab) {
        api.tabs.emit(tab, "create_tag", data);
      });
    }, function(e) {
      data.success = false;
      api.tabs.emit(tab, "create_tag", data);
    });
  },
  /**
   * Makes a request to the server to add a tag to a keep.
   * 
   * ADD
   *   Request URL: https://api.kifi.com/site/keeps/add
   *   Request Method: POST
   *   Request Payload: {
   *     "collectionId":"f033afe4-bbb9-4609-ab8b-3e8aa968af21",
   *     "keeps":[{
   *       "title":"Use JSDoc: Index",
   *       "url":"http://usejsdoc.org/index.html"
   *     }]
   *   }
   *   Response: {
   *     "keeps": [{
   *       "id":"220c1ac7-6644-477f-872b-4088988d7810",
   *       "title":"Use JSDoc: Index",
   *       "url":"http://usejsdoc.org/index.html",
   *       "isPrivate":false
   *     }],
   *     "addedToCollection":1
   *   }
   */
  add_tag: function(data, _, tab) {
    log("[add_tag]", data)();
    ajax("POST", "https://api.kifi.com/site/keeps/add", data, function(o) {
      log("[add_tag] response:", o)();
      data.success = true;
      pageData[tab.nUri].tabs.forEach(function(tab) {
        api.tabs.emit(tab, "add_tag", data);
      });
    }, function(e) {
      data.success = false;
      api.tabs.emit(tab, "add_tag", data);
    });
  },
  /**
   * Makes a request to the server to remove a tag from a keep.
   * 
   * REMOVE
   *   Request URL: https://api.kifi.com/site/collections/dc76ee74-a141-4e96-a65f-e5ca58ddfe04/removeKeeps
   *   Request Method: POST
   *   Request Payload: ["88ed8dc9-a20d-49c6-98ef-1b554533b106"]
   *   Response: {"removed":1}
   */
  remove_tag: function(data, _, tab) {
    log("[remove_tag]", data)();
    var collectionId = data.collectionId,
      keepId = data.keepId;
    ajax("POST", "https://api.kifi.com/site/collections/" + collectionId + "/removeKeeps", [keepId], function(o) {
      log("[remove_tag] response:", o)();
      data.success = true;
      pageData[tab.nUri].tabs.forEach(function(tab) {
        api.tabs.emit(tab, "remove_tag", data);
      });
    }, function(e) {
      data.success = false;
      api.tabs.emit(tab, "remove_tag", data);
    });
  },
