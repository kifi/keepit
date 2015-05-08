'use strict';

angular.module('kifi')

.constant('KeepSelection', (function () {

  function SetSelection() {
    this.keeps = new window.Set();
  }
  SetSelection.prototype = {
    selectAll: function (keeps) {
      for (var i = 0, n = keeps.length; i < n; i++) {
        this.keeps.add(keeps[i]);
      }
    },
    unselectAll: function () {
      this.keeps.clear();
    },
    isSelected: function (keep) {
      return this.keeps.has(keep);
    },
    isSelectedAll: function (keeps) {
      return keeps && keeps.length && keeps.length === this.keeps.size;
    },
    toggleSelect: function (keep) {
      if (this.keeps.has(keep)) {
        this.keeps['delete'](keep);
      } else {
        this.keeps.add(keep);
      }
    },
    toggleSelectAll: function (keeps) {
      if (this.isSelectedAll(keeps)) {
        this.unselectAll();
      } else {
        this.selectAll(keeps);
      }
    },
    getSelectedLength: function () {
      return this.keeps.size;
    },
    getSelected: function (keeps) {
      return _.filter(keeps, function (keep) {
        return this.has(keep);
      }, this.keeps);
    }
  };

  function ArraySelection() {
    this.keeps = [];
  }
  ArraySelection.prototype = {
    selectAll: function (keeps) {
      this.keeps = _.union(this.keeps, keeps);
    },
    unselectAll: function () {
      this.keeps.length = 0;
    },
    isSelected: function (keep) {
      return this.keeps.indexOf(keep) >= 0;
    },
    isSelectedAll: function (keeps) {
      return keeps && keeps.length && keeps.length === this.keeps.length;
    },
    toggleSelect: function (keep) {
      var i = this.keeps.indexOf(keep);
      if (i >= 0) {
        this.keeps.splice(i, 1);
      } else {
        this.keeps.push(keep);
      }
    },
    toggleSelectAll: function (keeps) {
      if (this.isSelectedAll(keeps)) {
        this.unselectAll();
      } else {
        this.selectAll(keeps);
      }
    },
    getSelectedLength: function () {
      return this.keeps.length;
    },
    getSelected: function (keeps) {
      return _.intersection(keeps, this.keeps);
    }
  };

  return typeof window.Set === 'function' ? SetSelection : ArraySelection;

}()));
