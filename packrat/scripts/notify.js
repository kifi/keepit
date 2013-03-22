// @match /^https?:\/\/[^\/]*\/.*/
// @require styles/notify.css
// @require scripts/lib/jquery-1.8.2.min.js
// @require scripts/lib/jquery-showhover.js
// @require scripts/lib/keymaster.min.js
// @require scripts/lib/mustache-0.7.1.min.js
// @require scripts/api.js
// @require scripts/render.js
// @require scripts/lib/notification.js

var notify = function() {
  api.port.on({
    notification: function(data) {
      var details = data[0].details;
      if (details) {
        switch (data[0].category) {
          case "comment":
            KifiNotification.add({
              title: details.author.firstName + ' ' + details.author.lastName,
              contentHtml: details.text,
              link: details.title,
              image: details.author.avatar,
              sticky: false,
              showForMs: 7000,
              clickAction: function () {
                var win=window.open(details.url, '_blank');
                win.focus();
              }
            });
            break;
          case "message":
            KifiNotification.add({
              title: details.author.firstName + ' ' + details.author.lastName,
              contentHtml: details.text,
              link: details.title,
              image: details.author.avatar,
              sticky: false,
              showForMs: 7000,
              clickAction: function () {
                var win=window.open(details.url, '_blank');
                win.focus();
              }
            });
            break;
        }
      }
    }
  });
}();