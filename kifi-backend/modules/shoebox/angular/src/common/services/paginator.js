'use strict';

angular.module('kifi')

.factory('Paginator', [
  function () {
    function Paginator(sourceFunction, fetchPageSize, whenDone) {
      this.sourceFunction = sourceFunction;
      this.fetchPageSize = fetchPageSize || 12;
      this.whenDone = whenDone || Paginator.DONE_WHEN_RESPONSE_IS_LESS_THAN_A_FULL_PAGE;
      this.reset();
    }
    Paginator.DONE_WHEN_RESPONSE_IS_EMPTY = 'DONE_WHEN_RESPONSE_IS_EMPTY';
    Paginator.DONE_WHEN_RESPONSE_IS_LESS_THAN_A_FULL_PAGE = 'DONE_WHEN_RESPONSE_IS_LESS_THAN_A_FULL_PAGE';

    Paginator.prototype.reset = function () {
      this.fetchPageNumber = 0;
      this.hasMoreItems = true;
      this.init = false;
      this.loading = false;
      this.items = [];
      this._cachedFetch = null;
    };

    Paginator.prototype._calcServerHasMore = function (items, pageSize) {
      if (this.whenDone === Paginator.DONE_WHEN_RESPONSE_IS_EMPTY) {
        var responseIsEmpty = (items.length === 0);
        return !responseIsEmpty; // has more if not empty
      } else if (this.whenDone === Paginator.DONE_WHEN_RESPONSE_IS_LESS_THAN_A_FULL_PAGE) {
        // continue paging if response size is at least a full page OR if pageSize is null (for APIs that let the server choose the page size)
        var lessThanFullPage = (pageSize || this.fetchPageSize) && (items.length < (pageSize || this.fetchPageSize));
        return !lessThanFullPage;
      } else {
        return true; // otherwise, continue indefinitely
      }
    };

    Paginator.prototype.fetch = function (sourceFunction, pageNumber, pageSize, refresh) {
      if (!refresh && this.loading) {
        return this._cachedFetch;
      }

      sourceFunction = sourceFunction || this.sourceFunction;
      pageNumber = pageNumber || this.fetchPageNumber;
      pageSize = pageSize || this.fetchPageSize;

      this.loading = true;
      this.fetchPageNumber++;

      var fetchPromise = sourceFunction(pageNumber, pageSize)
        .then(function (items) {
          this.hasMoreItems = this._calcServerHasMore(items, pageSize);
          this.init = true;

          if (refresh) {
            this.items = items;
            this.fetchPageNumber = 0;
          } else {
            this.items = this.items.concat(items);
          }

          return this.items;
        }.bind(this))
        ['catch'](function () {
          this.fetchPageNumber--;
        }.bind(this))
        ['finally'](function () {
          this.loading = false;
          this._cachedFetch = null;
        }.bind(this));

      this._cachedFetch = fetchPromise;

      return fetchPromise;
    };

    Paginator.prototype.resetAndFetch = function () {
      this.reset();
      return this.fetch.apply(this, arguments);
    };

    Paginator.prototype.hasLoaded = function () {
      return this.init === true && this.loading === false;
    };

    Paginator.prototype.hasMore = function () {
      return this.hasMoreItems;
    };

    return Paginator;
  }
]);
