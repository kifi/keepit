!function () {
  var $top = document.querySelector('.k-top');
  var $btn = document.querySelector('.k-download');
  var rTop = $top.getBoundingClientRect();
  var rBtn = $btn.getBoundingClientRect();
  var y = rTop.height - rBtn.height - 2 * (rTop.bottom - rBtn.bottom);
  var fixed = false;
  document.addEventListener('scroll', function (e) {
    if ((document.body.scrollTop > y) !== fixed) {
      if ((fixed = !fixed)) {
        $top.style.cssText = 'position:fixed;top:-' + y + 'px';
        $top.nextElementSibling.style.marginTop = rTop.height + 'px';
      } else {
        $top.removeAttribute('style');
        $top.nextElementSibling.removeAttribute('style');
      }
    }
  });
  document.querySelector('.k-iphone').classList.remove('k-out');
}();
