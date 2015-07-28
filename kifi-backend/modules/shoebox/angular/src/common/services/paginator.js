'use strict';

angular.module('kifi')

.factory('Paginator', [
  function () {
    function Paginator(sourceFunction, fetchPageSize) {
      this.sourceFunction = sourceFunction;
      this.fetchPageSize = fetchPageSize || 12;

      this.reset();
    }

    Paginator.prototype.reset = function () {
      this.fetchPageNumber = 0;
      this.hasMoreItems = true;
      this.loading = false;
      this.items = [];
      this._cachedFetch = null;
    };

    Paginator.prototype.fetch = function (sourceFunction, pageNumber, pageSize) {
      if (this.loading) {
        return this._cachedFetch;
      }

      sourceFunction = sourceFunction || this.sourceFunction;
      pageNumber = pageNumber || this.fetchPageNumber;
      pageSize = pageSize || this.fetchPageSize;

      this.loading = true;

      var fetchPromise = sourceFunction(pageNumber, pageSize)
        .then(function (items) {
          this.hasMoreItems = (items.length === this.fetchPageSize);  // important to do before filtering below
          this.fetchPageNumber++;
          this.loading = false;

          this.items = this.items.concat(items);

          return this.items;
        }.bind(this))
        ['finally'](function () {
          this._cachedFetch = null;
        }.bind(this));

      this._cachedFetch = fetchPromise;

      return fetchPromise;
    };

    Paginator.prototype.resetAndFetch = function () {
      this.reset();
      return this.fetch.apply(this, arguments);
    };

    Paginator.prototype.hasMore = function () {
      return this.hasMoreItems;
    };

    return Paginator;
  }
]);
