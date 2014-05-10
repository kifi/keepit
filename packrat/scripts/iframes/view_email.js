if (/^Mac/.test(navigator.platform)) {
  document.documentElement.className = 'mac';
}

var msgId = location.hash.match(/#(.+&)*msgId=([^&]+)(&|$)/)[2];

var request = new XMLHttpRequest();
request.onreadystatechange = function() {
  if (request.readyState == XMLHttpRequest.DONE) {
    if (request.status == 200) {
      document.open();
      document.write(request.responseText);
      document.close();
    } else {
      document.querySelector('.kifi-waiting').style.display = 'none';
      document.querySelector('.kifi-error').style.display = 'block';
    }
  }
}

request.open('GET', '/eliza/emailPreview/' + msgId, true);
request.send();
