/********************************************************
  Module dependencies
 ********************************************************/

var gulp = require('gulp');
var stylus = require('gulp-stylus');
var rimraf = require('gulp-rimraf');
var runSequence = require('run-sequence');
var map = require('vinyl-map');
var concat = require('gulp-concat');
var rename = require('gulp-rename');
var cssmin = require('gulp-cssmin');
var nib = require('nib');
var lazypipe = require('lazypipe');
var plumber = require('gulp-plumber');
var livereload = require('gulp-livereload');
var es = require('event-stream');
var cache = require('gulp-cached');
var remember = require('gulp-remember');
var fs = require('fs');
var ngHtml2Js = require('gulp-ng-html2js');
var minifyHtml = require('gulp-minify-html');
var concat = require('gulp-concat');
var uglify = require('gulp-uglify');
var gulpif = require('gulp-if');
var spritesmith = require('gulp.spritesmith');
var jshint = require('gulp-jshint');
var connect = require('gulp-connect');
var modRewrite = require('connect-modrewrite');
var karma = require('karma').server;

/********************************************************
  Globals
 ********************************************************/

var outDir = 'dist';
var tmpDir = 'tmp';
var pkgName = JSON.parse(fs.readFileSync('package.json')).name;
var banner = fs.readFileSync('banner.txt').toString();
var isRelease = false;

/********************************************************
  Paths
 ********************************************************/

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
};

var stylFiles = ['src/**/*.styl', '!src/common/build-css/*.styl'];
var jsFiles = 'src/**/*.js';
var htmlFiles = 'src/**/*.html';
var testJsFiles = 'test/**/*.js';

/**
 * For each library dependency, provide either just one file, or a pair [development file, production file] (typically [non-minified, minified])
 */
var libCssFiles = [
  'lib/normalize-css/normalize.css',
  ['managed-lib/jquery-ui-1.10.4.custom/css/smoothness/jquery-ui-1.10.4.custom.css', 'managed-lib/jquery-ui-1.10.4.custom/css/smoothness/jquery-ui-1.10.4.custom.min.css'],
  'managed-lib/pace/pace.css'
];
var libJsFiles = [
  ['lib/lodash/dist/lodash.js', 'lib/lodash/dist/lodash.min.js'],
  ['lib/underscore.string/lib/underscore.string.js', 'lib/underscore.string/dist/underscore.string.min.js'],
  ['lib/jquery/dist/jquery.js', 'lib/jquery/dist/jquery.min.js'],
  ['lib/angular/angular.js', 'lib/angular/angular.min.js'],
  ['lib/angular-cookies/angular-cookies.js', 'lib/angular-cookies/angular-cookies.min.js'],
  ['lib/angular-resource/angular-resource.js', 'lib/angular-resource/angular-resource.min.js'],
  ['lib/angular-sanitize/angular-sanitize.js', 'lib/angular-sanitize/angular-sanitize.min.js'],
  ['lib/angular-route/angular-route.js', 'lib/angular-route/angular-route.min.js'],
  ['lib/angular-animate/angular-animate.js', 'lib/angular-animate/angular-animate.min.js'],
  'lib/jquery.mousewheel/jquery.mousewheel.js',
  'lib/antiscroll/antiscroll.js',
  ['lib/moment/moment.js', 'lib/moment/min/moment.min.js'],
  'lib/angular-moment/angular-moment.js',
  ['lib/angular-facebook-api/dist/angular-facebook-api.js', 'lib/angular-facebook-api/dist/angular-facebook-api.min.js'],
  ['managed-lib/jquery-ui-1.10.4.custom/js/jquery-ui-1.10.4.custom.js', 'managed-lib/jquery-ui-1.10.4.custom/js/jquery-ui-1.10.4.custom.min.js'],
  'managed-lib/ui-slider/slider.js',
  'managed-lib/libs.js',
  'managed-lib/angular-smart-scroll/src/angular-smart-scroll.js',
  'lib/angulartics/dist/angulartics.min.js',
  ['lib/fuse.js/src/fuse.js', 'lib/fuse.js/src/fuse.min.js']
];

var devFiles = function (files) {
  return files.map(function (file) {
    if (typeof file === 'string') {
      return file;
    } else {
      return file[0];
    }
  });
};

var prodFiles = function (files) {
  return files.map(function (file) {
    if (typeof file === 'string') {
      return file;
    } else {
      return file[1];
    }
  });
};

/********************************************************
  Caches
 ********************************************************/

var stylesCache = 'styles';
var jsCache = 'js';
var htmlCache = 'html';
var jsHintSrcCache = 'jshint-src';
var jsHintTestCache = 'jshint-test';

var cacheUpdater = function (cacheName) {
  return function (event) {
    if (event.type === 'deleted') {
      for(var i = 0; i < arguments.length; i++) {
        var cacheName = arguments[arg];
        delete cache.caches[cacheName][event.path];
        remember.forget(cacheName, event.path);
      }
    }
  }
};

/********************************************************
  Lazyipes
 ********************************************************/

var makeMinJs = lazypipe()
  .pipe(uglify)
  .pipe(rename, {suffix: '.min'})
  .pipe(gulp.dest, outDir);

var makeMinCss = lazypipe()
  .pipe(cssmin, {keepSpecialComments: 1})
  .pipe(rename, {suffix: '.min'})
  .pipe(gulp.dest, outDir);

var makeTemplates = lazypipe()
  .pipe(minifyHtml, {
    empty: true,
    spare: true,
    quotes: true
  })
  .pipe(ngHtml2Js, {
    moduleName: pkgName + '.templates'
  });

/********************************************************
  Tasks
 ********************************************************/

gulp.task('clean-tmp', function () {
  return gulp.src(tmpDir, {read: false})
    .pipe(rimraf());
});

gulp.task('clean', function () {
  return gulp.src([outDir, tmpDir], {read: false})
    .pipe(rimraf());
});

gulp.task('styles', function () {
  return gulp.src(stylFiles, {base: './'})
    .pipe(cache(stylesCache))
    .pipe(stylus({use: [nib()], import: ['nib', __dirname + '/src/common/build-css/*.styl']}))
    .pipe(remember(stylesCache))
    .pipe(concat(pkgName + '.css'))
    .pipe(gulpif(!isRelease, gulp.dest(outDir)))
    .pipe(gulpif(isRelease, makeMinCss()));
});

gulp.task('jshint', function () {
  var srcHint = gulp.src(jsFiles)
    .pipe(cache(jsHintSrcCache))
    .pipe(jshint('.jshintrc'))
    .pipe(jshint.reporter('jshint-stylish'));

  var testHint = gulp.src(testJsFiles)
    .pipe(cache(jsHintTestCache))
    .pipe(jshint('test/.jshintrc'))
    .pipe(jshint.reporter('jshint-stylish'));

  return es.merge(srcHint, testHint);
});

gulp.task('scripts', ['jshint'], function () {
  var html2js = gulp.src(htmlFiles)
    .pipe(cache(htmlCache))
    .pipe(makeTemplates());

  var scripts = gulp.src(jsFiles)
    .pipe(cache(jsCache));

  return es.merge(html2js, scripts)
    .pipe(remember(htmlCache))
    .pipe(remember(jsCache))
    .pipe(concat(pkgName + '.js'))
    .pipe(gulpif(!isRelease, gulp.dest(outDir)))
    .pipe(gulpif(isRelease, makeMinJs()));
});

gulp.task('lib-styles', function () {
  return gulp.src(devFiles(libCssFiles))
    .pipe(concat('lib.css'))
    .pipe(gulp.dest(outDir));
});

gulp.task('lib-min-styles', function () {
  return gulp.src(prodFiles(libCssFiles))
    .pipe(gulpif(['**/*.css','!**/*.min.css'], cssmin({keepSpecialComments: 1})))
    .pipe(concat('lib.min.css'))
    .pipe(gulp.dest(outDir));
});

gulp.task('lib-scripts', function () {
  return gulp.src(devFiles(libJsFiles))
    .pipe(concat('lib.js'))
    .pipe(gulp.dest(outDir));
});

gulp.task('lib-min-scripts', function () {
  return gulp.src(prodFiles(libJsFiles))
    .pipe(gulpif(['**/*.js','!**/*.min.js'], uglify()))
    .pipe(concat('lib.min.js'))
    .pipe(gulp.dest(outDir))
});

gulp.task('watch', function () {
  livereload.listen();
  gulp.watch(stylFiles, ['styles']).on('change', cacheUpdater(stylesCache));
  gulp.watch(jsFiles, ['scripts']).on('change', cacheUpdater(jsCache, jsHintSrcCache, jsHintTestCache));
  gulp.watch(htmlFiles, ['scripts']).on('change', cacheUpdater(htmlCache));
  gulp.watch(libCssFiles, ['lib-styles']);
  gulp.watch(libJsFiles, ['lib-scripts']);
  gulp.watch(outDir + '/**').on('change', livereload.changed);
});

gulp.task('sprite-base-2x', function () {
  var spriteData = gulp.src('img/sprites/*.png').pipe(spritesmith({
    imgName: 'sprites.png',
    cssName: 'sprites.styl',
    algorithm: 'binary-tree',
    padding: 2,
    cssFormat: 'stylus',
    cssVarMap: function (sprite) {
      // useful to override template variables
    }
  }));
  spriteData.img.pipe(gulp.dest('img'));
  spriteData.css.pipe(gulp.dest('src/common/build-css'));
});

gulp.task('sprite-base-css-2x', function () {
  var spriteData = gulp.src('img/sprites/*.png').pipe(spritesmith({
    imgName: 'sprites.png',
    cssName: 'spriteClasses.styl',
    algorithm: 'binary-tree',
    padding: 2,
    cssFormat: 'stylus',
    cssTemplate: 'src/common/build-css/spritesClasses.styl.tpl',
    cssVarMap: function (sprite) {
      var cssSelector = '';
      var pseudoClasses = ['hover', 'active'];
      var names = sprite.name.split('-');
      var modifier = names[names.length - 1];
      var root = names.slice(0, names.length - 1).join('-');

      if (sprite.width % 2 !== 0 || sprite.height % 2 !== 0) {
        grunt.fail.warn("sprite " + sprite.name + " is not retina: " + sprite.width + " x " + sprite.height);
      }

      if (pseudoClasses.indexOf(modifier) !== -1) {
        cssSelector += '.sprite-' + sprite.name + ',\n.sprite-' + root + ':' + modifier + '\n  sprite2x($' + sprite.name + ')\n';
      } else if (modifier === 'default') {
        cssSelector += '.sprite-' + root + '\n  sprite2x($' + sprite.name + ')\n';
      } else {
        cssSelector += '.sprite-' + sprite.name + '\n  sprite2x($' + sprite.name + ')\n';
      }
      sprite.cssSelector = cssSelector;
    }
  }));
  spriteData.img.pipe(gulp.dest('img'));
  spriteData.css.pipe(gulp.dest('src/common'));
});

gulp.task('sprite', ['sprite-base-2x', 'sprite-base-css-2x']);

gulp.task('templates', function () {
  return gulp.src(htmlFiles)
    .pipe(cache(htmlCache))
    .pipe(makeTemplates())
    .pipe(concat(pkgName + '-tpl.js'))
    .pipe(gulp.dest(tmpDir));
})

gulp.task('run-tests', ['templates'], function (done) {
  karma.start({
    frameworks: ['jasmine'],
    files: [
      'dist/lib.js',
      'lib/angular-mocks/angular-mocks.js',
      tmpDir + '/' + pkgName + '-tpl.js',
      'src/**/*.js',
      'test/**/*.js'
    ],
    reporters: ['dots'],
    colors: true,
    autoWatch: true,
    browsers: ['PhantomJS'],
    captureTimeout: 60000,
    singleRun: true
  }, done);
});

gulp.task('test', function () {
  runSequence('templates', 'run-tests', 'clean-tmp');
});

// Note: suboptimal use of connect: it already includes livereload (but part of the livereload API is not available)
// Should switch to https://github.com/schickling/gulp-webserver when middleware is supported
gulp.task('server-dev', function() {
  connect.server({
    port: 8080,
    host: 'dev.ezkeep.com',
    fallback: 'dev.html',
    middleware: function () {
      return [modRewrite(['^(/|/index.html)$ /dev.html'])];
    }
  });
});

gulp.task('build-prod', function () {
  isRelease = true;
  runSequence('clean', ['sprite', 'styles', 'scripts', 'lib-min-styles', 'lib-min-scripts']);
});

gulp.task('prod', ['build-prod'], function () {
  connect.server({
    port: 8080,
    host: 'dev.ezkeep.com',
    fallback: 'index.html'
  });
});

gulp.task('default', function () {
  runSequence('clean', ['sprite', 'styles', 'scripts', 'lib-styles', 'lib-scripts'], ['watch', 'server-dev']);
});
