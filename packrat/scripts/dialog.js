// @require styles/dialog.css
// @require scripts/lib/jquery.js
// @require scripts/lib/mustache.js
// @require scripts/render.js

kifiDialog = function() {
  function showLoginDialog() {
    render("html/login_dialog", {
      logo: api.url("images/kifi_logo_medium.png")
    }, function(html) {
      if ($('.kifi-message-dialog-wrapper').length) {
        removeDialog();
        return;
      }
      $wrapper = $('<div/>').addClass('kifi-message-dialog-wrapper').html(html).appendTo('body');

      var $overlay = $('<div class="kifi-dialog-overlay"></div>');
      $overlay.appendTo('body');

      var $dialog = $('.kifi-message-dialog');
      setTimeout(function() {
        $wrapper.addClass('kifi-dialog-show');
      }, 1);

      function removeDialog() {
        $wrapper = $('.kifi-message-dialog-wrapper');
        $wrapper.removeClass("kifi-dialog-show");
        document.removeEventListener("keydown", onKeyDown, true);
        setTimeout(function() {
          $('.kifi-message-dialog-wrapper').remove();
          $('.kifi-dialog-overlay').remove();
        }, 500);
      }

      $dialog.on('click', '.kifi-dialog-cancel', function (ev) {
        ev.stopPropagation();
        removeDialog();
      });
      $dialog.find('.kifi-facebook').on('click', function() {
        document.location = "https://www.kifi.com/login/facebook";
        removeDialog();
        return false;
      });
      $dialog.find('.kifi-linkedin').on('click', function() {
        document.location = "https://www.kifi.com/login/linkedin";
        removeDialog();
        return false;
      });
      $overlay.on('click', removeDialog);

      document.addEventListener("keydown", onKeyDown, true);
      function onKeyDown(e) {
        if (e.keyCode == 27 && !e.metaKey && !e.ctrlKey && !e.shiftKey) { 
          removeDialog();
        }
      }
    });
  }
  return {
    showLoginDialog: function() {
      showLoginDialog();
    }
  };
}();