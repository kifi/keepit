(function () {
  if ((document.documentElement.dataset || {}).kifiExt) {
    proceed();
  } else {
    window.addEventListener('message', function (e) {
      if ((e.data || {}).type === 'kifi_ext_listening') {
        proceed();
      }
    });
  }

  var errorCount = 0; // number of retries

  var $doc = $('html');
  var supported = $doc.hasClass('supported');
  var isChrome = $doc.hasClass('chrome');
  var isChromeSupported = isChrome && supported;
  var isFirefox = $doc.hasClass('firefox');
  var isFirefoxSupported = isFirefox && supported;
  var browserName = isChrome ? 'Chrome' : (isFirefox ? 'Firefox' : '');


  function initPageView() {
    $('.browser-name').html(browserName);
    if (isChrome) {
      if (supported) {
        $('.install-button').css('display', 'block');
        $('.chrome-webstore-badge').css('display', 'block');
      } else { // old version
        $('.install-old').css('display', 'block');
      }

    } else if (isFirefox) {
      if (supported) {
        $('.install-button').css('display', 'block');
      } else { // old version
        $('.install-old').css('display', 'block');
      }

    } else { // not chrome or firefox
      $('.install-other').css('display', 'block');
    }

  }
  initPageView();

  $(document).on('click', '.install-button[href]', function () {
    var $a = $(this);
    var $aContent = $a.find('.install-button-content');
    var $doc = $('html');
    if (errorCount === 0) {
      $a.data('html', $a.html());
    }
    $a.removeAttr('href');
    $aContent.text('Installing...');

    reportToThirdPartyCampaigns();

    if ($doc.hasClass('chrome')) {
      chrome.webstore.install($('link[rel="chrome-webstore-item"]').attr('href'), function () {
        console.log('[chrome.webstore.install] done');
        $aContent.text('Installed. Initializing...');
        setTimeout(troubleshootChrome.bind(null, $a), 8000);
      }, function (e) {
        console.log('[chrome.webstore.install] error:', e);
        if (++errorCount < 4) {
          $aContent.text('Had an error. Try again?');
          setTimeout(troubleshootChrome.bind(null, $a), 1500);
        } else {
          $aContent.hide();
          $('.install-error').show();
          $('.continue-link').show();
        }
      })
    } else if ($doc.hasClass('firefox')) {
      InstallTrigger.install({
        Kifi: {
          URL: '/extensions/firefox/kifi.xpi',
          IconURL: '/extensions/firefox/kifi.png'
        }
      });
      setTimeout(troubleshootFirefox.bind(null, $a), 8000);
    }
  });

  function restoreInstallLink($a) {
    $a.html($a.data('html')).attr('href', 'javascript:');
  }

  function troubleshootFirefox($a) {
    restoreInstallLink($a);
    $('.install-ff-help:hidden').slideDown();
  }

  function troubleshootChrome($a) {
    restoreInstallLink($a);
    $('.install-chrome-help:hidden').slideDown();
  }

  function reportToThirdPartyCampaigns() {
    $.ajax({
      url: "https://cjsab.com/p.ashx?o=34878&f=js&t=INSTALL_PAGE_CHROME",
      xhrFields: {
        withCredentials: true
      }
    });
    $.ajax({
      url: "https://cjsab.com/p.ashx?o=34877&f=js&t=INSTALL_PAGE_CHROME",
      xhrFields: {
        withCredentials: true
      }
    });
    $.ajax({
      url: "https://byvue.com/p.ashx?o=35028&f=js&t=INSTALL_PAGE_CHROME",
      xhrFields: {
        withCredentials: true
      }
    });
  }

  function proceed() {
    window.location.href = '/';
  }
}());
