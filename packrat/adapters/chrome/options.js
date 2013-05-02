$(function() {
  var port = chrome.runtime.connect({name: ""});
  port.onMessage.addListener(function(msg) {
    if (msg[0] == "api:respond") {
      [init, showSession][msg[1]](msg[2]);
    }
  });
  port.postMessage(["get_prefs",,0]);
  function init(o) {
    console.log("[init] prefs:", o.prefs);
    $env.find("[value=" + o.prefs.env + "]").prop("selected", true);
    $showSlider.prop("checked", o.prefs.showSlider);
    $maxResults.val(o.prefs.maxResults).data("val", o.prefs.maxResults);
    $showScores.prop("checked", o.prefs.showScores);
    showSession(o.session);
  }
  var $env = $(chrome.runtime.id === "fpjooibalklfinmkiodaamcckfbcjhin" ? null : "select").show().change(function() {
    port.postMessage(["set_env", this.value]);
  });
  var $showSlider = $("[name=show_slider]").click(function() {
    port.postMessage(["set_prefs", {showSlider: this.checked}]);
  });
  var $maxResults = $("[name=max_results]").on("input", function() {
    var n = +this.value.trim();
    if (n) {
      n = Math.round(Math.max(1, Math.min(9, n)));
      port.postMessage(["set_prefs", {maxResults: n}]);
      $maxResults.data("val", n);
    }
  }).blur(function() {
    this.value = $maxResults.data("val");
  });
  var $showScores = $("[name=show_scores]").click(function() {
    port.postMessage(["set_prefs", {showScores: this.checked}]);
  });
  $("#log-out").click(function(e) {
    e.preventDefault();
    port.postMessage(["log_out",,1]);
  });
  $("#log-in").click(function(e) {
    e.preventDefault();
    port.postMessage(["log_in",,1]);
  });
  function showSession(session) {
    console.log("[showSession] session:", session);
    $("#avatar").remove();
    if (session) {
      $("#name").text(session.name).before("<img id=avatar src='" + cdnBase + "/users/" + session.userId + "/pics/100/0.jpg'>");
    }
    $("#session").attr("class", session ? "valid" : "none");
  }
});
