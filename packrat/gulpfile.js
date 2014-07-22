var gulp = require('gulp');
var rimraf = require('gulp-rimraf');
var rename = require('gulp-rename');
var map = require('vinyl-map');
var stylus = require('gulp-stylus');
var runSequence = require('run-sequence');
var gulpif = require('gulp-if');
var clone = require('gulp-clone');
var css = require('css');
var es = require('event-stream');
var fs = require('fs');
var jeditor = require("gulp-json-editor");
var lazypipe = require('lazypipe');
var reload = require('./gulp/livereload.js');
var watch = require('gulp-watch');
var plumber = require('gulp-plumber');
var print = require('gulp-print');

var outDir = 'out';
var adapterFiles = ['adapters/chrome/**', 'adapters/firefox/**', '!adapters/chrome/manifest.json', '!adapters/firefox/package.json'];
var sharedAdapterFiles = ['adapters/shared/*.js', 'adapters/shared/*.min.map'];
var resourceFiles = ['icons/**', 'images/**', 'media/**', 'scripts/**', '!scripts/lib/rwsocket.js'];
var rwsocketScript = 'scripts/lib/rwsocket.js';
var backgroundScripts = [
  'main.js',
  'threadlist.js',
  'lzstring.min.js',
  'scorefilter.js',
  'friend_search_cache.js',
  'livereload.js'
];
var tabScripts = ['scripts/**'];
var htmlFiles = 'html/**/*.html';
var styleFiles = 'styles/**/*.styl';

var contentScripts = {};
var styleDeps = {};
var scriptDeps = {};
var asap = {};

// Used to take the union of glob descriptors
var union = function () {
  return Array.prototype.reduce.call(arguments, function(a, b) {
    if (typeof b === 'string') {
      a.push(b);
      return a;
    } else {
      return a.concat(b);
    }
  }, []);
}

// gulp-json-editor but with prettier printing
var jeditor = (function () {
  var editor = require("gulp-json-editor");

  return function (transform) {
    return (lazypipe()
      .pipe(editor, transform)
      .pipe(map, function (code) {
        return JSON.stringify(JSON.parse(code.toString()), undefined, 2);
      }))();
  }
})();

gulp.task('clean', function () {
  return gulp.src(outDir, {read: false})
    .pipe(rimraf());
});

gulp.task('copy', function () {
  var adapters = gulp.src(adapterFiles, {base: './adapters'})
    .pipe(gulp.dest(outDir));

  var shared = gulp.src(sharedAdapterFiles)
    .pipe(gulp.dest(outDir + '/chrome'))
    .pipe(gulp.dest(outDir + '/firefox/lib'));

  var resources = gulp.src(resourceFiles, { base: './' })
    .pipe(gulp.dest(outDir + '/chrome'))
    .pipe(gulp.dest(outDir + '/firefox/data'));

  var rwsocket = gulp.src(rwsocketScript)
    .pipe(gulp.dest(outDir + '/chrome'))
    .pipe(gulp.dest(outDir + '/firefox/data/scripts/lib'));

  var background = gulp.src(backgroundScripts)
    .pipe(gulp.dest(outDir + '/chrome'))
    .pipe(gulp.dest(outDir + '/firefox/lib'));

  return es.merge(adapters, shared, resources, rwsocket, background);
});

gulp.task('html2js', function () {
  var html2js = map(function (code, filename) {
    var contents = code.toString()
      .replace(/\s*\n\s*/g, ' ')
      .replace(/'/g, '\\\'')
      .trim();
    var relativeFilename = filename.replace(new RegExp('^' + __dirname + '/'), '');
    if (/^html\/iframes\/.*$/.test(relativeFilename)) {
      return 'document.body.innerHTML=\'' + contents + '\';\n';
    } else {
      var baseFilename = relativeFilename.replace(/\.[^/.]+$/, ""); // strip file extension
      return 'render.cache[\'' + baseFilename + '\']=\'' + contents + '\';\n';
    }
  });

  return gulp.src(htmlFiles, {base: './'})
    .pipe(html2js)
    .pipe(rename(function (path) {
      path.extname = '.js';
    }))
    .pipe(gulp.dest(outDir + '/chrome/scripts'))
    .pipe(gulp.dest(outDir + '/firefox/data/scripts'));
});

gulp.task('scripts', ['html2js', 'copy'], function () {
  var chromeInjectionFooter = map(function (code, filename) {
    var relativeFilename = filename.replace(new RegExp('^' + __dirname + '/' + outDir + '/chrome/'), '');
    var shortName = relativeFilename.replace(/^scripts\//, '');
    return code.toString() + 'api.injected["' + relativeFilename + '"]=1;\n//@ sourceURL=http://kifi/' + shortName + '\n';
  });

  return gulp.src([outDir + '/chrome/scripts/**/*.js', '!**/iframes/**'], {base: outDir})
    .pipe(chromeInjectionFooter)
    .pipe(gulp.dest(outDir));
});

var rewriteStyles = (function () {

  function insulateSelectors(rule) {
    if (rule.selectors) {
      rule.selectors = rule.selectors.map(function (selector) {
        return selector.replace(/(\.[a-zA-Z0-9_-]*)(?![^(]*\))/, '$1$1$1');
      });
    }
  }

  function insulateRule(rule) {
    insulateSelectors(rule);
    if (rule.rules) {
      rule.rules.map(insulateRule);
    }
  }

  var insulate = function () {
    return map(function (code) {
      // too slow (and probably wrong):
      //return line.replace(/^(([^().]*|\([^)]*\))*)(\.[a-zA-Z0-9_-]*).*$/gm, '$1$3$3$3');

      // reasonably fast (but slower than sed):
      /*return code.toString().split('\n').map(function (line) {
        return line.replace(/(\.[a-zA-Z0-9_-]*)(?![^(]*\))/m, '$1$1$1');
      }).join('\n');*/

      // a bit slower than above, but much safer and more readable/maintainable
      var obj = css.parse(code.toString())
      obj.stylesheet.rules.map(insulateRule);
      return css.stringify(obj);
    });
  };

  return lazypipe()
    .pipe(print, function (filepath) {
      return 'Processing ' + filepath;
    })
    .pipe(function () {
      return gulpif(/[.]styl$/, stylus());
    })
    .pipe(function () {
      return gulpif(RegExp('^(?!' + __dirname + '/styles/(insulate\\.|iframes/))'), insulate())
    });
})();

var chromifyStyles = lazypipe()
  .pipe(map, function (code) {
    return code.toString().replace(/\/images\//g, 'chrome-extension://__MSG_@@extension_id__/images/');
  })
  .pipe(gulp.dest, outDir + '/chrome');

var firefoxifyStyles = lazypipe()
  .pipe(map, function (code) {
    return code.toString().replace(/\/images\//g, 'resource://kifi-at-42go-dot-com/kifi/data/images/');
  })
  .pipe(gulp.dest, outDir + '/firefox/data');

gulp.task('styles', function () {
  var preprocessed = gulp.src(styleFiles, {base: './'})
    .pipe(rewriteStyles());

  var chrome = preprocessed
    .pipe(clone())
    .pipe(chromifyStyles());

  var firefox = preprocessed
    .pipe(clone())
    .pipe(firefoxifyStyles());

  return es.merge(chrome, firefox);
});

gulp.task('extractMeta', function () {
  var extractMeta = map(function (code, filename) {
    var relativeFilename = filename.replace(new RegExp('^' + __dirname + '/'), '');
    var re = /^\/\/ @(match|require|asap)([^\S\n](.+))?$/gm;
    var content = code.toString();
    while ((match = re.exec(content)) !== null) {
      if (match[1] === 'match') {
        contentScripts[relativeFilename] = match[3];
      } else if (match[1] === 'require') {
        var dep = match[3].trim();
        var depMatch = dep.match(/^(.*)\.([^.]+)$/)
        if (depMatch) {
          var base = depMatch[1];
          var ext = depMatch[2];
          if (ext === 'css' || ext === 'styl') {
            styleDeps[relativeFilename] = styleDeps[relativeFilename] || [];
            styleDeps[relativeFilename].push(base + '.css');
          } else if (ext === 'js') {
            scriptDeps[relativeFilename] = scriptDeps[relativeFilename] || [];
            scriptDeps[relativeFilename].push(dep);
          }
        }
      } else if (match[1] === 'asap') {
        asap[relativeFilename] = true;
      }
    }
  });

  return gulp.src(tabScripts)
    .pipe(extractMeta)
});

gulp.task('meta', ['extractMeta'], function () {
  var contentScriptItems = [];
  for (var f in contentScripts) {
    contentScriptItems.push('["' + f + '", ' + contentScripts[f] + ', ' + (asap[f] ? 1 : 0) + ']');
  }
  var contentScriptsString = ' [\n  ' + contentScriptItems.join(',\n  ') + ']';
  var styleDepsString = JSON.stringify(styleDeps, undefined, 2);
  var scriptDepsString = JSON.stringify(scriptDeps, undefined, 2);

  var chromeMeta = 'meta = {\n  contentScripts:' +
    contentScriptsString +
    ',\n  styleDeps: ' +
    styleDepsString +
    ',\n  scriptDeps: ' +
    scriptDepsString +
    '};';

  var firefoxMeta = 'exports.contentScripts =' +
    contentScriptsString +
    ';\nexports.styleDeps = ' +
    styleDepsString +
    ';\nexports.scriptDeps = ' +
    scriptDepsString +
    ';';

  fs.writeFile(outDir + '/chrome/meta.js', chromeMeta);
  fs.writeFile(outDir + '/firefox/lib/meta.js', firefoxMeta);
});

gulp.task('config', ['copy'], function () {
  var version = fs.readFileSync('build.properties', {encoding: 'utf8'})
    .match('version=(.*)')[1].trim();

  var chromeConfig = gulp.src('adapters/chrome/manifest.json')
    .pipe(rename('chrome/manifest.json'))
    .pipe(jeditor(function(json) {
      json.version = version;
      json.background.scripts.push('livereload.js');
      return json;
    }))
    .pipe(gulp.dest(outDir))

  var firefoxConfig = gulp.src('adapters/firefox/package.json')
    .pipe(rename('firefox/package.json'))
    .pipe(jeditor(function(json) {
      json.version = version;
      return json;
    }))
    .pipe(gulp.dest(outDir))

  return es.merge(chromeConfig, firefoxConfig);
});

/**
 * Takes a set of "actions" (lazypipes or task names), and creates corresponding
 * watchers. "actions" can be either lazypipes (good!), or task names (not as good,
 * because the task is run on all files, not just the modified ones)
 */
function watchAndReload(target) {
  var actions = Array.prototype.slice.call(arguments, 1);
  var tasks = [];
  var pipes = [];

  actions.forEach(function (a) {
    if (typeof a === 'string') {
      tasks.push(a);
    } else {
      pipes.push(a);
    }
  });

  if (tasks) {
    gulp.watch(target, function () {
      // the tasks array is modified by runSequence, need to pass a copy
      runSequence(tasks.slice(0), reload);
    });
  }

  if (pipes) {
    gulp.src(target, {base: './'})
      .pipe(watch(function(files) {
        var withLazypipe = pipes.map(function (pipe) {
          return files.pipe(pipe());
        });
        return es.merge.apply(null, withLazypipe)
          .pipe(es.wait(reload));
      }));
  }
}

gulp.task('watch', function() {
  watchAndReload(union(
    adapterFiles,
    sharedAdapterFiles,
    resourceFiles,
    rwsocketScript,
    backgroundScripts,
    htmlFiles
  ), 'scripts', 'meta');

  watchAndReload(styleFiles, 'meta', rewriteStyles.pipe(chromifyStyles));
});

gulp.task('default', function () {
  runSequence('clean', ['watch', 'scripts', 'styles', 'meta', 'config']);
});
