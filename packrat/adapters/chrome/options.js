$(function() {
  chrome.runtime.sendMessage(["get_prefs"], function init(o) {
    console.log("[init] prefs:", o.prefs);
    $("[name=env][value=" + o.prefs.env + "]").prop("checked", true);
    $("#show-slider").prop("checked", o.prefs.showSlider);
    $("#max-results").val(o.prefs.maxResults);
    $("#show-scores").prop("checked", o.prefs.showScores);
    showSession(o.session);
  });
  $("#save").click(function() {
    chrome.runtime.sendMessage(["set_prefs", {
      env: $("input[name=env][value=development]").is(":checked") ? "development" : "production",
      showSlider: $("#show-slider").is(":checked"),
      maxResults: $("#max-results").val(),
      showScores: $("#show-scores").is(":checked")}]);
    window.close();
  });
  $("#log-out").click(function(e) {
    e.preventDefault();
    chrome.runtime.sendMessage(["log_out"], function() {
      showSession();
    });
  });
  $("#log-in").click(function(e) {
    e.preventDefault();
    chrome.runtime.sendMessage(["log_in"], function(session) {
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
