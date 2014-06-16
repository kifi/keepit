$.fn.layout = function () {
  return this.each(function () {
    this.clientHeight;  // forces layout
  });
};