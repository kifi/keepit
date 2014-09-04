'use strict';

angular.module('kifi')

.factory('recoDecoratorService', ['keepDecoratorService',
  function (keepDecoratorService) {
    // Exact copy of what is in Keep constructor. Need to DRY.
    // TODO: move this into util.
    function formatTitleFromUrl(url) {
      var aUrlParser = document.createElement('a');
      var secLevDomainRe = /[^.\/]+(?:\.[^.\/]{1,3})?\.[^.\/]+$/;
      var fileNameRe = /[^\/]+?(?=(?:\.[a-zA-Z0-9]{1,6}|\/|)$)/;
      var fileNameToSpaceRe = /[\/._-]/g;

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
      fileName = fileName.replace(fileNameToSpaceRe, ' ').trimRight().trimLeft();

      return domain + (fileName ? ' · ' + fileName : '');
    }

    function Recommendation(rawReco, type) {
      this.recoData = {
        type: type,
        kind: rawReco.kind,
        reasons: rawReco.metaData || []
      };

      this.recoData.reasons.forEach(function (reason) {
        if (!reason.name) {
          reason.name = formatTitleFromUrl(reason.url);
        }
      });

      this.recoKeep = new keepDecoratorService.Keep(rawReco.itemInfo, 'reco');
    }

    var api = {
      newUserRecommendation: function (rawReco) {
        return new Recommendation(rawReco, 'recommended');
      },

      newPopularRecommendation: function (rawReco) {
        return new Recommendation(rawReco, 'popular');
      }
    };

    return api;
  }
]);
