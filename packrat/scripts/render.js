function render(path, params, partialPaths, callback) {
  // sort out partialPaths and callback (both optional)
  if (!callback && typeof partialPaths == "function") {
    callback = partialPaths;
    partialPaths = undefined;
  }

  loadPartials(path.replace(/[^\/]*$/, ""), partialPaths || {}, function(partials) {
    loadFile(path, function(template) {
      callback(Mustache.render(template, params, partials));
    });
  });

  function loadPartials(basePath, paths, callback) {
    var partials = {}, names = Object.keys(paths), numLoaded = 0;
    if (!names.length) {
      callback(partials);
    } else {
      names.forEach(function(name) {
        loadFile(basePath + paths[name], function(tmpl) {
          partials[name] = tmpl;
          if (++numLoaded == names.length) {
            callback(partials);
          }
        });
      });
    }
  }

  function loadFile(path, callback) {
    var tmpl = render.cache[path];
    if (tmpl) {
      callback(tmpl);
    } else {
      api.load(path, function(tmpl) {
        callback(render.cache[path] = tmpl);
      });
    }
  }
}
render.cache = {};
