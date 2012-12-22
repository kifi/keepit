$(function() {
  chrome.extension.sendMessage({type: "get_conf"}, function(o) {
    console.log("config:", o);
    var env = o.config.env;
    $("[name=env][value=" + env + "]").attr("checked", true);
    $("#max_search_results").val(o.config["max_res"]);
    $("#hover_timeout").val(o.config["hover_timeout"]);
    $("input[name=scores]").attr("checked", o.config["show_score"] == "true");
    $(document.body)
    .prepend($("<p>", {id: "name", text: o.session.name}))
    .prepend("<img src='" + o.session.avatarUrl + "'>");
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
