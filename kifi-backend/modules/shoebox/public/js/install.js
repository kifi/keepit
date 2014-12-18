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

  var errorCount = 0;

  $(document).on('click', '.install-button[href]', function () {
    var $a = $(this);
    var $doc = $('html');
    if (errorCount === 0) {
      $a.data('html', $a.html());
    }
    $a.removeAttr('href').text('Installing...');

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

    if ($doc.hasClass('chrome')) {
      chrome.webstore.install($('link[rel="chrome-webstore-item"]').attr('href'), function () {
        console.log('[chrome.webstore.install] done');
        $a.text('Installed. Initializing...');
      }, function (e) {
        console.log('[chrome.webstore.install] error:', e);
        if (++errorCount < 4) {
          $a.text('Had an error. Try again?');
          setTimeout(restoreInstallLink.bind(null, $a), 1500);
        } else {
          $a.hide();
          $('.install-error').show();
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

  function proceed() {
    window.location.href = '/';
  }
}());
