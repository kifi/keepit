function log() {
  'use strict';
  if (self.options && self.options.dev) {
    var d = new Date, ds = d.toString(), a = Array.slice(arguments);
    for (var i = 0; i < a.length; i++) {
      var v = a[i];
      if (typeof v == "object") {
        try {
          a[i] = JSON.stringify(v);
        } catch (e) {
          a[i] = String(v) + "{" + Object.keys(v).join(",") + "}";
        }
      }
    }
    a.unshift("'" + ds.substr(0,2) + ds.substr(15,9) + "." + String(+d).substr(10) + "'");
    console.log.apply(console, a);
  }
}

self.port.on('play_sound', playSound);

var audioCache = {};
function playSound(file) {
  'use strict';

  if (!file) {
    return;
  }

  log('[soundWorker:playSound]', file);

  var audio = audioCache[file];
  if (!audio) {
    audio = audioCache[file] = new Audio(file);
    document.body.appendChild(audio);
  }
  audio.play();
}
