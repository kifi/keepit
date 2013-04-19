$(function() {
  var isChrome = !!(window.chrome && chrome.webstore && chrome.webstore.install);
  var isFirefox = ('MozBoxSizing' in document.documentElement.style) || navigator.userAgent.toLowerCase().indexOf('firefox') > -1;

  if(isChrome || isFirefox) {
    $("#other-install").hide();
    $(".install_extensions").show();
  } else {
    $("#other-install").show();
    $(".install_extensions").hide();
    $("#install_extensions_title").hide();
  }

  if(isChrome) {
    $("#chrome-install").show();
    var errorCount = 0;
    $("#chrome-install").click(function() {
      $this = $(this);
      $this.text("Installing...");

      chrome.webstore.install("https://chrome.google.com/webstore/detail/fpjooibalklfinmkiodaamcckfbcjhin", function() {
        $this.text("Installed. Preparing extension now...");
      }, function(e) {
        errorCount = errorCount+1;
        if(errorCount < 2) {
          $this.text("Hrm, there's a problem. Try again?");
        } else {
          $this.hide();
          $this.parent().append("<h3>There seems to be a problem. Hang tight, we'll take a look. Feel free to try again later.</h2>");
        }
      })
    });
  } else if(isFirefox) {
    $("#firefox-install").show();
    $("#firefox-install").click(function() {
      $(this).text("Installing...");
      document.location = "https://www.kifi.com/assets/plugins/kifi-beta.xpi";
    });
  }
});
