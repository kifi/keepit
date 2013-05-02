$(function() {
  var main = chrome.extension.getBackgroundPage(), api = main.api, env = api.prefs.get("env");

  console.log("pref:", api.prefs.get("maxResults"));

  $(chrome.runtime.id === "fpjooibalklfinmkiodaamcckfbcjhin" ? null : "select").show()
  .find("[value=" + env + "]").prop("selected", true).end()
  .change(function() {
    api.prefs.set("env", this.value);
    chrome.runtime.reload();
  });

  $("[name=show_slider]").prop("checked", api.prefs.get("showSlider")).click(function() {
    api.prefs.set("showSlider", this.checked);
  });
  $("[name=max_results]").val(api.prefs.get("maxResults")).data("val", api.prefs.get("maxResults"))
  .on("input", function() {
    var n = +this.value.trim();
    if (n) {
      n = Math.round(Math.max(1, Math.min(9, n)));
      api.prefs.set("maxResults", n);
      $(this).data("val", n);
    }
  }).blur(function() {
    this.value = $(this).data("val");
  });
  $("[name=show_scores]").prop("checked", api.prefs.get("showScores")).click(function() {
    api.prefs.set("showScores", this.checked);
  });

  $("#log-out").click(function(e) {
    e.preventDefault();
    main.deauthenticate();
    showSession();
  });
  $("#log-in").click(function(e) {
    e.preventDefault();
    main.authenticate(showSession);
  });

  function showSession() {
    var s = main.session;
    console.log("[showSession] session:", s);
    if (s) {
      var cdnBase = env == "development" ?
        "dev.ezkeep.com:9000" : //d1scct5mnc9d9m.cloudfront.net
        "djty7jcqog9qu.cloudfront.net";
      $("#name").text(s.name);
      $("#identity").css("backgroundImage", "url(http://" + cdnBase + "/users/" + s.userId + "/pics/100/0.jpg)");
    }
    $("#session").attr("class", s ? "valid" : "none");
  }
  showSession();
});
