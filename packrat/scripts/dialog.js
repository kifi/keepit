// @require styles/dialog.css
// @require scripts/lib/jquery.js
// @require scripts/lib/mustache.js
// @require scripts/render.js

$.fn.layout = $.fn.layout || function() {
  return this.each(function() {this.clientHeight});  // forces layout
};

kifiDialog = function() {
  
  function toggleLoginDialog() {
    render("html/login_dialog", {
      logo: api.url("images/kifi_logo_medium.png")
    }, function(html) {
      if ($('.kifi-message-dialog-wrapper').length) {
        removeDialog();
        return;
      }
      var $wrapper = $('<div>').addClass('kifi-message-dialog-wrapper').html(html).appendTo('body');

      var $overlay = $('<div class="kifi-dialog-overlay">');
      $overlay.appendTo('body');

      var $dialog = $('.kifi-message-dialog');
      $wrapper.layout().addClass('kifi-dialog-show')

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
      $dialog.on('click', '.kifi-facebook', function() {
        document.location = "https://www.kifi.com/login/facebook";
        removeDialog();
        return false;
      });
      $dialog.on('click', '.kifi-linkedin', function() {
        document.location = "https://www.kifi.com/login/linkedin";
        removeDialog();
        return false;
      });
      $overlay.on('click', removeDialog);

      document.addEventListener("keydown", onKeyDown, true);
      function onKeyDown(e) {
        if (e.keyCode == 27 && !e.metaKey && !e.ctrlKey && !e.shiftKey) { 
          removeDialog();
          return false;
        }
      }
    });
  }
  return {
    toggleLoginDialog: function() {
      toggleLoginDialog();
    }
  };
}();