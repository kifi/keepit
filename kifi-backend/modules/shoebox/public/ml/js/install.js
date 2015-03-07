(function () {

  function detectBrowserVersion() {
    var ua= navigator.userAgent, tem,
    M= ua.match(/(opera|chrome|safari|firefox|msie|trident(?=\/))\/?\s*(\d+)/i) || [];
    if(/trident/i.test(M[1])){
        tem=  /\brv[ :]+(\d+)/g.exec(ua) || [];
        return 'IE '+(tem[1] || '');
    }
    if(M[1]=== 'Chrome'){
        tem= ua.match(/\b(OPR|Edge)\/(\d+)/);
        if(tem!= null) return tem.slice(1).join(' ').replace('OPR', 'Opera');
    }
    M= M[2]? [M[1], M[2]]: [navigator.appName, navigator.appVersion, '-?'];
    if((tem= ua.match(/version\/(\d+)/i))!= null) M.splice(1, 1, tem[1]);
    return M;
  }

  var browserInfo = detectBrowserVersion();
  var browser = browserInfo[0];
  var browserVersion = browserInfo[1];

  $('.browser-name').html(browser);
  var isChrome = (browser === 'Chrome');
  var isFirefox = (browser === 'Firefox');

  if (isChrome) {
    if (browserVersion >= 32) {
      $('.install-button').css('display', 'initial');
      $('.install-button').attr('href','https://chrome.google.com/webstore/detail/Kifi/fpjooibalklfinmkiodaamcckfbcjhin');
      $('.chrome-webstore-badge').css('display', 'block');
    } else { // old version
      $('.install-old').css('display', 'initial');
    }

  } else if (isFirefox) {
    if (browserVersion >= 20) {
      $('.install-button').css('display', 'initial');
    } else { // old version
      $('.install-old').css('display', 'initial');
    }

  } else {
    // unsupported browser
    $('.install-other').css('display', 'initial');
    $('.install-button').css('display','none');
  }

}());
