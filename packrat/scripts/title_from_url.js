var formatTitleFromUrl = (function () {
  var aUrlParser = document.createElement('a');
  var secLevDomainRe = /[^.\/]+(?:\.[^.\/]{1,3})?\.[^.\/]+$/;
  var fileNameRe = /[^\/]+?(?=(?:\.[a-zA-Z0-9]{1,6}|\/|)$)/;
  var fileNameToSpaceRe = /[\/._-]/g;

  return function (url, matches, markupMatch) {  // pass matches and markupMatch to mark up (e.g. bold) matches
    aUrlParser.href = url;

    var domain = aUrlParser.hostname;
    var domainIdx = url.indexOf(domain);
    var domainMatch = domain.match(secLevDomainRe);
    if (domainMatch) {
      domainIdx += domainMatch.index;
      domain = domainMatch[0];
    }

    var fileName = aUrlParser.pathname;
    var fileNameIdx = url.indexOf(fileName, domainIdx + domain.length);
    var fileNameMatch = fileName.match(fileNameRe);
    if (fileNameMatch) {
      fileNameIdx += fileNameMatch.index;
      fileName = fileNameMatch[0];
    }
    fileName = fileName.replace(fileNameToSpaceRe, ' ').trimRight();

    for (var i = matches && matches.length; i--;) {
      var match = matches[i];
      var start = match[0], len = match[1];
      if (start >= fileNameIdx && start < fileNameIdx + fileName.length) {
        fileName = markupMatch(fileName, start - fileNameIdx, len);
      } else if (start >= domainIdx && start < domainIdx + domain.length) {
        domain = markupMatch(domain, start - domainIdx, len);
      }
    }
    fileName = fileName.trimLeft();

    return domain + (fileName ? ' · ' + fileName : '');
  };
}());
