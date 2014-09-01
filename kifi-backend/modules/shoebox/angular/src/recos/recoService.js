'use strict';

angular.module('kifi')

.factory('recoService', [
  function () {
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

    function Keep (rawKeep) {
      if (!rawKeep) {
        return {};
      }

      _.assign(this, rawKeep);

      this.urlId = this.id;

      // This is needed to make keep/unkeep etc. to work.
      // Compare normal keeps with keeps in search results.
      // See unkeep() function in keepService.js.
      // TODO: this should be updated when we refactor KeepService.
      delete this.id;

      // Helper functions.
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

      function shouldShowSmallImage(summary) {
        var imageWidthThreshold = 200;
        return (summary.imageWidth && summary.imageWidth < imageWidthThreshold) || summary.description;
      }

      function hasSaneAspectRatio(summary) {
        var aspectRatio = summary.imageWidth && summary.imageHeight && summary.imageWidth / summary.imageHeight;
        var saneAspectRatio = aspectRatio > 0.5 && aspectRatio < 3;
        var bigEnough = summary.imageWidth + summary.imageHeight > 200;
        return bigEnough && saneAspectRatio;
      }

      function getKeepReadTime(summary) {
        var read_times = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 20, 30, 60];

        var wc = summary && summary.wordCount;
        if (wc < 0) {
          return null;
        } else {
          var minutesEstimate = wc / 250;
          var found = _.find(read_times, function (t) { return minutesEstimate < t; });
          return found ? found + ' min' : '> 1 h';
        }
      }

      // Add new properties to the keep.
      this.keepType = 'reco';

      this.titleAttr = this.title || this.url;
      this.titleHtml = this.title || formatTitleFromUrl(this.url);
      this.hasSmallImage = this.summary && shouldShowSmallImage(this.summary) && hasSaneAspectRatio(this.summary);
      this.hasBigImage = this.summary && (!shouldShowSmallImage(this.summary) && hasSaneAspectRatio(this.summary));
      this.readTime = getKeepReadTime(this.summary);
      this.showSocial = this.others || (this.keepers && this.keepers.length > 0);

      // Tags are still messed up right now.
      this.tagList = [];
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

      this.recoKeep = new Keep(rawReco.itemInfo);
    }

    var api = {
      UserRecommendation: function (rawReco) {
        return new Recommendation(rawReco, 'recommended');
      },

      PopularRecommendation: function (rawReco) {
        return new Recommendation(rawReco, 'popular');
      }
    };

    return api;
  }
]);
