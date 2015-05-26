$.fn.repairInputs = function () {  // crbug.com/484291
  'use strict';
  if ($(document.body.firstChild).is('embed[type="application/pdf"]')) {
    this.on('keydown', 'input,textarea', function (e) {
      var s, i, j, d;
      switch (e.keyCode) {
        case 8: // BKSP
          s = this.value, i = this.selectionStart, j = this.selectionEnd;
          if (i === j) {
            if (i > 0) {
              this.value = s.slice(0, i - 1) + s.slice(i);
              this.setSelectionRange(i - 1, i - 1);
              $(this).trigger('input');
              e.preventDefault();
            }
          } else {
            this.value = s.slice(0, i) + s.slice(j);
            this.setSelectionRange(i, i);
            $(this).trigger('input');
            e.preventDefault();
          }
          break;
        case 46: // DEL
          s = this.value, i = this.selectionStart, j = this.selectionEnd;
          if (i === j) {
            if (i < s.length) {
              this.value = s.slice(0, i) + s.slice(i + 1);
              this.setSelectionRange(i, i);
              $(this).trigger('input');
              e.preventDefault();
            }
          } else {
            this.value = s.slice(0, i) + s.slice(j);
            this.setSelectionRange(i, i);
            $(this).trigger('input');
            e.preventDefault();
          }
          break;
        case 37: // LEFT
          i = this.selectionStart, j = this.selectionEnd, d = this.selectionDirection;
          if (i === j || d === 'backward') {
            if (i > 0) {
              this.setSelectionRange(i - 1, e.shiftKey ? j : i - 1, i === j && e.shiftKey ? 'backward' : d);
              e.preventDefault();
            } else if (i < j && !e.shiftKey) {
              this.setSelectionRange(0, 0);
              e.preventDefault();
            }
          } else {
            this.setSelectionRange(e.shiftKey ? i : j - 1, j - 1, d);
            e.preventDefault();
          }
          break;
        case 39: // RIGHT
          s = this.value, i = this.selectionStart, j = this.selectionEnd, d = this.selectionDirection;
          if (i === j || d !== 'backward') {
            if (j < s.length) {
              this.setSelectionRange(e.shiftKey ? i : j + 1, j + 1, i === j && e.shiftKey ? 'forward' : d);
              e.preventDefault();
            } else if (i < j && !e.shiftKey) {
              this.setSelectionRange(j, j);
              e.preventDefault();
            }
          } else {
            this.setSelectionRange(i + 1, e.shiftKey ? j : i + 1, d);
            e.preventDefault();
          }
          break;
      }
    });
  }
  return this;
};
