document.querySelector('.k-iphone').classList.remove('k-out');

setTimeout(function initScrolling() {
  var $top = document.querySelector('.k-top');
  var $topPar = $top.parentNode;
  var fixed = false;
  var iScr = new IScroll(document.body, {probeType: 3, disableMouse: true, disablePointer: true});
  debugger;
  iScr.on('scroll', fixTop);
  iScr.on('scrollEnd', fixTop);
  function fixTop(e) {
    var y = this.y << 0;
    if ((y < -367) !== fixed) {
      fixed = !fixed;
      $top.parentNode.removeChild($top);
      $top.style.position = fixed ? 'fixed' : '';
      $top.style.top = fixed ? '-367px' : '';
      var p = fixed ? $topPar.parentNode : $topPar;
      p.insertBefore($top, p.firstChild);
      setTimeout(iScr.refresh.bind(iScr), 0);
    }
  }
  document.addEventListener('touchmove', function (e) { e.preventDefault() }, false);
}, 100);
