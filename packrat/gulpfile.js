var gulp = require('gulp');
var rimraf = require('gulp-rimraf');
var rename = require('gulp-rename');
var map = require('vinyl-map');
var less = require('gulp-less');
var runSequence = require('run-sequence');
var gulpif = require('gulp-if');
var clone = require('gulp-clone');
var css = require('css');
var es = require('event-stream');
var livereload = require('gulp-livereload');

var outDir = 'out';
var adapterFiles = ['adapters/chrome/**', 'adapters/firefox/**'];
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
var htmlFiles = 'html/**/*.html';
var styleFiles = 'styles/**/*.*';

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
    return code.toString() + 'api.injected[' + relativeFilename + ']=1;\n//@ sourceURL=http://kifi/' + shortName + '\n';
  });

  return gulp.src([outDir + '/chrome/scripts/**/*.js', '!**/iframes/**'], {base: outDir})
    .pipe(chromeInjectionFooter)
    .pipe(gulp.dest(outDir))
    .pipe(livereload());
});

gulp.task('styles', function () {
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

  var insulate = map(function (code) {
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

  var imageUrlChrome = map(function (code) {
    return code.toString().replace(/\/images\//g, 'chrome-extension://__MSG_@@extension_id__/images/');
  });

  var imageUrlFirefox = map(function (code) {
    return code.toString().replace(/\/images\//g, 'resource://kifi-at-42go-dot-com/kifi/data/images/');
  });

  var preprocessed = gulp.src(styleFiles, {base: './'})
    .pipe(gulpif(/[.]less$/, less()))
    .pipe(gulpif(RegExp('^(?!' + __dirname + '/styles/(insulate\\.|iframes/))'), insulate))
  
  var chrome = preprocessed.pipe(clone())
    .pipe(imageUrlChrome)
    .pipe(gulp.dest(outDir + '/chrome'))
    .pipe(livereload());

  var firefox = preprocessed.pipe(clone())
    .pipe(imageUrlFirefox)
    .pipe(gulp.dest(outDir + '/firefox/data'));

  return es.merge(chrome, firefox);
});

gulp.task('watch', function() {
  livereload.listen();
  gulp.watch(union(
    adapterFiles,
    sharedAdapterFiles,
    resourceFiles,
    rwsocketScript,
    backgroundScripts,
    htmlFiles
  ), ['scripts']);
  gulp.watch(styleFiles, ['styles']);
});

gulp.task('default', function () {
  runSequence('clean', ['watch', 'scripts', 'styles']);
});
