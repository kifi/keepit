$(function() {
  chrome.extension.sendMessage(["get_prefs"], function init(o) {
    console.log("[init] prefs:", o.prefs);
    $("[name=env][value=" + o.prefs.env + "]").attr("checked", true);
    $("#max_search_results").val(o.prefs.max_res);
    $("#hover_timeout").val(o.prefs.hover_timeout);
    $("input[name=scores]").prop("checked", o.prefs.show_score);
    showSession(o.session);
  });
  $("#save").click(function() {
    chrome.extension.sendMessage(["set_prefs", {
      env: $("input[name=env][value=development]").is(":checked") ? "development" : "production",
      max_res: $("#max_search_results").val(),
      hover_timeout: $("#hover_timeout").val(),
      show_score: $("input[name=scores]").is(":checked")}]);
    window.close();
  });
  $("#log-out").click(function(e) {
    e.preventDefault();
    chrome.extension.sendMessage(["log_out"], function() {
      showSession();
    });
  });
  $("#log-in").click(function(e) {
    e.preventDefault();
    chrome.extension.sendMessage(["log_in"], function(session) {
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
