'use strict';

angular.module('kifi')

.factory('recoService', [
  '$http', 'env', '$q', '$timeout', 'routeService', 'Clutch',
  function ($http, env, $q, $timeout, routeService, Clutch) {
    var recos = [];

    function formatTitleFromUrl(url) {  // exact copy of what is in Keep constructor. Need to DRY.
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

      // This is needed to make keep/unkeep etc. to work.
      // Compare normal keeps with keeps in search results.
      // See unkeep() function in keepService.js.
      // TODO: figure out exactly why this distinction is there!
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

    var clutchParams = {
      cacheDuration: 2  // Not using the cache so we can see the loading :) 20000
    };

    function populateRecos(res, type) {
      recos = [];

      if (res && res.data) {
        res.data.forEach(function (rawReco) {
          recos.push(new Recommendation(rawReco, type));
        });
      }

      return recos;
    }

    var kifiRecommendationService = new Clutch(function (opts) {
      var recoOpts = {
        more: (!opts || opts.more === undefined) ? false : opts.more,
        recency: opts && angular.isNumber(opts.recency) ? opts.recency : 0.75
      };

      return $http.get(routeService.recos(recoOpts)).then(function (res) {
        return populateRecos(res, 'recommended');
      });
    }, clutchParams);

    var kifiPopularRecommendationService = new Clutch(function () {
      return $http.get(routeService.recosPublic()).then(function (res) {
        return populateRecos(res, 'popular');
      });
    }, clutchParams);

    var api = {
      get: function () {
        return recos.length > 0 ? 
          $q.when(recos) :
          kifiRecommendationService.get();
      },

      getMore: function (opt_recency) {
        return kifiRecommendationService.get({ more: true, recency: opt_recency });
      },

      getPopular: function () {
        return kifiPopularRecommendationService.get();
      },

      trash: function (keep) {
        $http.post(routeService.recoFeedback(), { 
          url: keep.url,
          feedback: {
            trashed: true
          }
        });
      },

      vote: function (keep, vote) {
        // vote === true -> upvote
        // vote === false -> downvote
        $http.post(routeService.recoFeedback(), {
          url: keep.url,
          feedback: {
            vote: vote
          }
        });
      },

      keep: function (keep) {
        $http.post(routeService.recoFeedback(), { 
          url: keep.url,
          feedback: {
            kept: true
          }
        });
      },

      click: function (keep) {
        $http.post(routeService.recoFeedback(), { 
          url: keep.url,
          feedback: {
            clicked: true 
          }
        });
      },

      improve: function (keep, improvement) {
        $http.post(routeService.recoFeedback(), { 
          url: keep.url,
          feedback: {
            improvement: improvement 
          }
        });
      }
    };

    return api;
  }
]);
