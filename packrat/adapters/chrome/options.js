$(function() {
  chrome.extension.sendMessage({type: "get_conf"}, function(config) {
    console.log("config:", config);
    var env = config.env;
    $("[name=env][value=" + env + "]").attr("checked", true);
    $("#max_search_results").val(config["max_res"]);
    $("#hover_timeout").val(config["hover_timeout"]);
    $("input[name=scores]").attr("checked", config["show_score"] == "true");
    $(document.body)
    .prepend($("<p>", {id: "name", text: config.user.name}))
    .prepend("<img src='" + config.user.avatar_url + "'>");
  });
  $("#save").click(function() {
    localStorage.env = $("input[name=env][value=development]").is(":checked") ? "development" : "production";
    set("max_res", $("#max_search_results").val());
    set("hover_timeout", $("#hover_timeout").val());
    set("show_score", $("input[name=scores]").is(":checked"));
    window.close();
  });
  function set(key, value) {
    chrome.extension.sendMessage({type: "set_conf", key: key, value: value});
  }
});
