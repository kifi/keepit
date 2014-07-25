var gulp = require('gulp');
var rimraf = require('gulp-rimraf');
var rename = require('gulp-rename');
var less = require('gulp-less');
var runSequence = require('run-sequence');
var gulpif = require('gulp-if');
var clone = require('gulp-clone');
var css = require('css');
var es = require('event-stream');
var fs = require('fs');
var jeditor = require("gulp-json-editor");
var lazypipe = require('lazypipe');
var watch = require('gulp-watch');
var plumber = require('gulp-plumber');
var print = require('gulp-print');
var nib = require('nib');
var header = require('gulp-header');
var zip = require('gulp-zip');
var shell = require('gulp-shell');
var concat = require('gulp-concat');
var reloader = require('./gulp/livereload.js');
var map = require('./gulp/vinyl-map.js');

var isRelease = false;

var outDir = 'out';
var tmpDir = 'tmp';

var adapterFiles = ['adapters/chrome/**', 'adapters/firefox/**', '!adapters/chrome/manifest.json', '!adapters/firefox/package.json'];
var sharedAdapterFiles = ['adapters/shared/*.js', 'adapters/shared/*.min.map'];
var resourceFiles = ['icons/**', 'images/**', 'media/**', 'scripts/**', '!scripts/lib/rwsocket.js'];
var rwsocketScript = 'scripts/lib/rwsocket.js';
var backgroundScripts = [
  'main.js',
  'threadlist.js',
  'lzstring.min.js',
  'scorefilter.js',
  'friend_search_cache.js'
];
var devBackgroundScripts = ['livereload.js']
var tabScripts = ['scripts/**'];
var htmlFiles = 'html/**/*.html';
var styleFiles = 'styles/**/*.*';

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
  return gulp.src([outDir, tmpDir], {read: false})
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

  var scripts = isRelease ? backgroundScripts : backgroundScripts.concat(devBackgroundScripts);

  var background = gulp.src(scripts)
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

var stylesPipe = (function () {

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

  var chromify = function () {
    return map(function (code) {
      return code.toString().replace(/\/images\//g, 'chrome-extension://__MSG_@@extension_id__/images/');
    });
  };

  var firefoxify = function () {
    return map(function (code, filename) {
      return code.toString().replace(/chrome-extension:\/\/__MSG_@@extension_id__\/images\//g, 'resource://kifi-at-42go-dot-com/kifi/data/images/');
    });
  }

  var mainStylesOnly = function (pipefun) {
    return function () {
      return gulpif(RegExp('^(?!' + __dirname + '/styles/(insulate\\.|iframes/))'), pipefun());
    }
  }

  return lazypipe()
    .pipe(print, function (filepath) {
      return 'Processing ' + filepath;
    })
    .pipe(function () {
      return gulpif(/[.]less$/, less());
    })
    .pipe(mainStylesOnly(insulate))
    .pipe(mainStylesOnly(chromify))
    .pipe(gulp.dest, outDir + '/chrome')
    .pipe(mainStylesOnly(firefoxify)) // order is important! firefoxify operates on chromified styles (makes code simpler - one unique stream)
    .pipe(gulp.dest, outDir + '/firefox/data');
})();

gulp.task('styles', function () {
  return gulp.src(styleFiles, {base: './'})
    .pipe(stylesPipe());
});

// Extracts metadata and creates corresponding snippet files
gulp.task('extract-meta', function () {
  var extractMeta = function (code, filename) {
    var relativeFilename = filename.replace(new RegExp('^' + __dirname + '/'), '');
    var re = /^\/\/ @(match|require|asap)([^\S\n](.+))?$/gm;
    var content = code.toString();
    var contentScriptRe = null;
    var styleDeps = [];
    var scriptDeps = [];
    var asap = 0;
    var match = null;
    while ((match = re.exec(content)) !== null) {
      if (match[1] === 'match') {
        contentScriptRe = match[3];
      } else if (match[1] === 'require') {
        var dep = match[3].trim();
        var depMatch = dep.match(/^(.*)\.([^.]+)$/)
        if (depMatch) {
          var base = depMatch[1];
          var ext = depMatch[2];
          if (ext === 'css' || ext === 'styl') {
            styleDeps.push(base + '.css');
          } else if (ext === 'js') {
            scriptDeps.push(dep);
          }
        }
      } else if (match[1] === 'asap') {
        asap = 1;
      }
    }

    return new Buffer((contentScriptRe ? 'contentScripts ' + relativeFilename + ' ' + asap + ' ' + contentScriptRe : '') + '\n' +
      styleDeps.map(function (styleDep) {
        return 'styleDeps ' + relativeFilename + ' ' + styleDep;
      }).join('\n') + '\n' +
      scriptDeps.map(function (scriptDep) {
        return 'scriptDeps ' + relativeFilename + ' ' + scriptDep;
      }).join('\n') + '\n');
  };

  return gulp.src(tabScripts, {base: './'})
    .pipe(map(extractMeta))
    .pipe(gulp.dest(tmpDir));
});

// Creates meta.js from snippet files
gulp.task('meta', ['extract-meta'], function () {
  var snippets = gulp.src(tmpDir + '/scripts/**')
    .pipe(concat('meta.js'))
    .pipe(map(function (code) {
      var content = code.toString();
      var contentScriptItems = [];
      var styleDeps = {};
      var scriptDeps = {};
      var re = /^(contentScripts|styleDeps|scriptDeps) (\S+) (.*)$/gm;
      var contentScriptRe = /^(\d) (.*)$/;
      var match = null;
      while ((match = re.exec(content)) !== null) {
        var type = match[1];
        var filename = match[2];
        var payload = match[3];
        if (type === 'contentScripts') {
          var payloadMatch = payload.match(contentScriptRe);
          var asap = payloadMatch[1], regex = payloadMatch[2];
          contentScriptItems.push('["' + filename + '", ' + regex + ', ' + asap + ']');
        } else {
          if (type === 'styleDeps') {
            styleDeps[filename] = styleDeps[filename] || [];
            styleDeps[filename].push(payload);
          } else if (type === 'scriptDeps') {
            scriptDeps[filename] = scriptDeps[filename] || [];
            scriptDeps[filename].push(payload);
          }
        }
      }
      var contentScriptsString = ' [\n  ' + contentScriptItems.join(',\n  ') + ']';
      return JSON.stringify([
        contentScriptsString,
        JSON.stringify(styleDeps, undefined, 2),
        JSON.stringify(scriptDeps, undefined, 2)
      ]);
    }))
  
  var chromeMeta = snippets.pipe(clone())
    .pipe(map(function (code) {
      var data = JSON.parse(code.toString());
      return 'meta = {\n  contentScripts:' + data[0] +
        ',\n  styleDeps: ' + data[1] +
        ',\n  scriptDeps: ' + data[2] +
        '};';
    }))
    .pipe(gulp.dest(outDir + '/chrome'));

  var firefoxMeta = snippets.pipe(clone())
    .pipe(map(function (code) {
      var data = JSON.parse(code.toString());
      return 'exports.contentScripts =' + data[0] +
        ';\nexports.styleDeps = ' + data[1] +
        ';\nexports.scriptDeps = ' + data[2] +
        ';';
    }))
    .pipe(gulp.dest(outDir + '/firefox/lib'));

  return es.merge(chromeMeta, firefoxMeta);
});

// Creates manifest.json (chrome) and package.json (firefox)
gulp.task('config', ['copy'], function () {
  var version = fs.readFileSync('build.properties', {encoding: 'utf8'})
    .match('version=(.*)')[1].trim();

  var chromeConfig = gulp.src('adapters/chrome/manifest.json')
    .pipe(rename('chrome/manifest.json'))
    .pipe(jeditor(function(json) {
      json.version = version;
      if (!isRelease) {
        json.background.scripts.push('livereload.js');
      }
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

gulp.task('config-package-chrome', ['config'], function () {
  gulp.src(outDir + '/chrome/manifest.json', {base: './'})
    .pipe(map(function (code) {
      return code.toString().replace(/(http|ws):\/\/dev\.ezkeep\.com:\d+\s*/g, '');
    }))
    .pipe(gulp.dest('.'));
});

gulp.task('zip-chrome', ['scripts', 'styles', 'meta', 'config-package-chrome'], function () {
  return gulp.src(outDir + '/chrome/**')
    .pipe(zip('kifi.zip'))
    .pipe(gulp.dest(outDir));
});

gulp.task('xpi-firefox', ['scripts', 'styles', 'meta', 'config'], shell.task([
  'cd ' + outDir + ' && \
  cfx xpi --pkgdir=firefox \
    --update-link=https://www.kifi.com/assets/plugins/kifi.xpi \
    --update-url=https://www.kifi.com/assets/plugins/kifi.update.rdf && \
  cd - > /dev/null'
]));

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
      runSequence(tasks.slice(0), reloader());
    });
  }

  if (pipes.length > 0) {
    var watchers = pipes.map(function (pipe) {
      return watch({glob:target, base: './', emitOnGlob: false})
        .pipe(plumber())
        .pipe(pipe());
    });
    es.merge.apply(null, watchers)
      .pipe(es.wait(reloader()));
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

  watchAndReload(styleFiles, 'meta', stylesPipe);
});

gulp.task('package', function () {
  isRelease = true;
  runSequence('clean', ['zip-chrome', 'xpi-firefox']);
});

gulp.task('default', function () {
  runSequence('clean', ['watch', 'scripts', 'styles', 'meta', 'config']);
});
