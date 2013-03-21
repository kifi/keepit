// @match /^https?:\/\/kifi\.com.*/
// @require styles/notify.css
// @require scripts/lib/jquery-1.8.2.min.js
// @require scripts/lib/jquery-showhover.js
// @require scripts/lib/keymaster.min.js
// @require scripts/lib/mustache-0.7.1.min.js
// @require scripts/api.js
// @require scripts/render.js
// @require scripts/lib/notification.min.js


KifiNotification.add({
  title: 'Alexander Willis Schultz',
  contentHtml: 'I commented on it above, but people I\'m talking to are hesitant to add on group because they are unsure of how to explain it.',
  link: 'Label Reading 101 Wellness City',
  image: 'https://fbcdn-profile-a.akamaihd.net/hprofile-ak-ash3/49938_508538138_1167343243_q.jpg',
  sticky: true,
  showForMs: 3000
});

var notify = function() {
  api.port.on({
    notification: function(data) {
      KifiNotification.add({
        title: 'New event',
        contentHtml: JSON.stringify(data),
        image: 'https://fbcdn-profile-a.akamaihd.net/hprofile-ak-ash3/49938_508538138_1167343243_q.jpg',
        sticky: false,
        showForMs: 1000
      });
    },
    event: function(data) {
      api.log("New event!!", data)
    }
  });
}();