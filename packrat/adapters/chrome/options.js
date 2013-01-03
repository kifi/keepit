$(function() {
  var config;
  chrome.extension.sendMessage({type: "get_conf"}, function init(o) {
    console.log("[init] config:", o.config);
    config = o.config;
    var env = o.config.env;
    $("[name=env][value=" + env + "]").attr("checked", true);
    $("#max_search_results").val(o.config["max_res"]);
    $("#hover_timeout").val(o.config["hover_timeout"]);
    $("input[name=scores]").attr("checked", !!o.config["show_score"]);
    showSession(o.session);
  });
  $("#save").click(function() {
    localStorage.env = $("input[name=env][value=development]").is(":checked") ? "development" : "production";
    set("max_res", $("#max_search_results").val());
    set("hover_timeout", $("#hover_timeout").val());
    set("show_score", $("input[name=scores]").is(":checked"));
    window.close();
    function set(key, value) {
      chrome.extension.sendMessage({type: "set_conf", key: key, value: value});
    }
  });
  $("#log-out").click(function(e) {
    e.preventDefault();
    chrome.extension.sendMessage({type: "log_out"}, function() {
      showSession();
    });
  });
  $("#log-in").click(function(e) {
    e.preventDefault();
    chrome.extension.sendMessage({type: "log_in"}, function(session) {
      showSession(session);
    });
  });
  function showSession(session) {
    console.log("[showSession] session:", session);
    $("#avatar").remove();
    if (session) {
      $("#name").text(session.name).before("<img id=avatar src='" + session.avatarUrl + "'>");
    }
    $("#session").attr("class", session ? "valid" : "none");
  }
});
