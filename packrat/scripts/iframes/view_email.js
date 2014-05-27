if (/^Mac/.test(navigator.platform)) {
  document.documentElement.className = 'mac';
}

(function () {
  'use strict';
  
  var msgId = location.hash.match(/#(.+&)*msgId=([^&]+)(&|$)/)[2];

  var request = new XMLHttpRequest();
  request.onloadend = function() {
    if (request.status == 200) {
      document.open();
      document.write(request.responseText);
      document.close();
    } else {
      document.querySelector('.kifi-waiting').style.display = 'none';
      document.querySelector('.kifi-error').style.display = 'block';
    }
  }
  request.withCredentials = true;

  request.open('GET', 'https://eliza.kifi.com/eliza/emailPreview/' + msgId, true);
  request.send();
}());
