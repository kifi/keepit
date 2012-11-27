
// Saves configs to localStorage.

function upload() {
  chrome.extension.sendRequest({type: "upload_all_bookmarks"}, function(response) {});
}

function resetUser() {
  chrome.extension.sendRequest({type: "remove_conf", key: "user"}, function(response) {
    chrome.extension.sendRequest({type: "remove_conf", key: "user_info"}, function(response) {
      restoreConfigs();
    });  
  });
}

function sendSaveConfig(key, value) {
  chrome.extension.sendRequest({type: "set_conf", key: key, value: value}, function(response) {});
}

function saveConfigs() {
  if ($("#env_prod").is(":checked")) {
    localStorage["env"] = "production";
  }
  else {
    localStorage["env"] = "development";
  }
  sendSaveConfig("max_res", $("#max_search_results").val());
  sendSaveConfig("hover_timeout", $("#hover_timeout").val());
  var showScore = $("#show_score").val();
    if (showScore === "yes" || showScore === true || showScore === "true") {
      showScore = true;
    } else {
      showScore = false;
    }
  sendSaveConfig("show_score", showScore);
  var uploadOnStart = $("#upload_on_start").val();
    if (uploadOnStart === "yes" || uploadOnStart === true || uploadOnStart === "true") {
      uploadOnStart = true;
    } else {
      uploadOnStart = false;
    }
  sendSaveConfig("upload_on_start", uploadOnStart);
  window.close();
}

function renderUserInfo(userInfo) {
  console.log(userInfo);
  $("#user_info_data").show();
  $("#user_name").html(userInfo.name);
  $("#user_avatar").attr("src", userInfo.avatar_url);
}

function restoreConfigs(callback) {
  chrome.extension.sendRequest({"type": "get_conf"}, function(config) {
    console.log("loaded config", config);
    var env = config["env"];
    if (env === "development") {
      $("#env_dev").attr("checked","checked");
    }
    if (env === "production") {
      $("#env_prod").attr("checked","checked");
    }
    $("#max_search_results").val(config["max_res"]);
    $("#hover_timeout").val(config["hover_timeout"]);
    var showScore = config["show_score"];
    if (showScore === "yes" || showScore === true) {
      showScore = "yes";
    } else {
      showScore = "no";
    }
    $("#show_score").val(showScore);
    $("#facebook_connect_link").click(function() {
      chrome.extension.sendRequest({"type": "get_conf"}, function(config) {
        var url = "http://" + config["server"] + "/authenticate/facebook";
        log("openning facebook window from " + url, "connect", "width=500,height=400");
        window.open(url);
        return false;
      });
    });  
    if (callback) {
      callback(config);
    }
  });
}
setTimeout(function() {
  restoreConfigs(function(config) {
    renderUserInfo(config["user"]);
  });
  $('#resetUser').click(resetUser);
  $('#upload').click(upload);
  $('#saveConfigs').click(saveConfigs);
}, 50);


