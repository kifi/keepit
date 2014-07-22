$(function () {
  var errorCount = 0;

  $(document).on('click', '.install-button[href]', function () {
    var $a = $(this);
    var $doc = $('html');
    if (errorCount === 0) {
      $a.data('html', $a.html());
    }
    $a.removeAttr('href').text('Installing...');

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
      window.location = 'https://www.kifi.com/assets/plugins/kifi.xpi';
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
});
