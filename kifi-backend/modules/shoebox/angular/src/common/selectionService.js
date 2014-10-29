'use strict';

angular.module('kifi')

.factory('selectionService', [

  function () {
    function Selection() {
      this.selected = {};
    }

    Selection.prototype.select = function (keep) {
      if (keep && keep.url) {
        this.selected[keep.url] = keep;
        return true;
      }
      return false;
    };

    Selection.prototype.unselect = function (keep) {
      if (keep && keep.url) {
        delete this.selected[keep.url];
        return true;
      }
      return false;
    };

    Selection.prototype.selectAll = function (keeps) {
      this.selected = _.reduce(keeps, function (map, keep) {
        if (keep && keep.url) {
          map[keep.url] = true;
        }
        return map;
      }, {});
    };

    Selection.prototype.unselectAll = function () {
      this.selected = {};
    };

    Selection.prototype.isSelected = function (keep) {
      return !!keep && keep.url && !!this.selected[keep.url];
    };

    Selection.prototype.isSelectedAll = function (keeps) {
      return keeps && keeps.length && keeps.length === this.getSelectedLength();
    };

    Selection.prototype.toggleSelect = function (keep) {
      if (this.isSelected(keep)) {
        return this.unselect(keep);
      } else {
        return this.select(keep);
      }
    };

    Selection.prototype.toggleSelectAll = function (keeps) {
      if (this.isSelectedAll(keeps)) {
        return this.unselectAll();
      } else {
        return this.selectAll(keeps);
      }
    };

    Selection.prototype.getSelectedLength = function () {
      return _.keys(this.selected).length;
    };

    Selection.prototype.getSelected = function (keeps) {
      var selected = this.selected;

      return keeps.filter(function (keep) {
        return keep.url in selected;
      });
    };

    Selection.prototype.getFirstSelected = function () {
      return _.values(this.selected)[0];
    };

    var api = {
      Selection: Selection
    };

    return api;
  }
]);
