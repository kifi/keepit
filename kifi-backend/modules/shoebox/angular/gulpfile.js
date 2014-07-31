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
var uglify = require('gulp-uglify');
var gulpif = require('gulp-if');
var spritesmith = require('gulp.spritesmith');
var jshint = require('gulp-jshint');
var connect = require('gulp-connect');
var modRewrite = require('connect-modrewrite');
var gutil = require('gulp-util');
var karma = require('karma').server;
var protractor = require('gulp-protractor').protractor;
var revall = require('gulp-rev-all');
var awspublish = require('gulp-awspublish');
var parallelize = require('concurrent-transform');
var through = require('through');

/********************************************************
  Globals
 ********************************************************/

var outDir = 'dist';
var tmpDir = 'tmp';
var pkgName = JSON.parse(fs.readFileSync('package.json')).name;
var banner = fs.readFileSync('banner.txt').toString();
var isRelease = false;

// awspublish
var aws = {
  'key': 'AKIAJZC6TMAWKQYEGBIQ',
  'secret': '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',
  'bucket': 'assets-b-prod',
  'region': 'us-west-1',
  'distributionId': null
};
var publisher = awspublish.create(aws);

/********************************************************
  Sort Stream
  https://github.com/dominictarr/sort-stream/blob/master/index.js
 ********************************************************/

var sort = function (comp) {
  var a = [];
  return through(function (data) {
    a.push(data);
  }, function () {
    a.sort(comp).forEach(this.queue);
    this.queue(null);
  });
};

var sortByName = function () {
  return sort(function (a, b) {
    var aName = a.relative, bName = b.relative;
    if (aName < bName) return -1;
    if (aName > bName) return 1;
    return 0
  });
};

/********************************************************
  Paths
 ********************************************************/

// Used to turn multiple glob descriptors into one
var flatten = function () {
  return Array.prototype.concat.apply([],arguments);
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
  'lib/jquery-mousewheel/jquery.mousewheel.js',
  'lib/antiscroll/antiscroll.js',
  ['lib/moment/moment.js', 'lib/moment/min/moment.min.js'],
  'lib/angular-moment/angular-moment.js',
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
  Servers
 ********************************************************/

function startDevServer(port) {
  connect.server({
    port: port || 8080,
    host: 'dev.ezkeep.com',
    fallback: 'dev.html',
    middleware: function () {
      return [modRewrite(['^(?!/(dist|img)) /dev.html'])];
    }
  });
}

function startProdServer(port) {
  connect.server({
    port: port || 8080,
    host: 'dev.ezkeep.com',
    fallback: 'index.html'
  });
}

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

gulp.task('sprite-imports', function () {
  var spriteData = gulp.src('img/sprites/*.png').pipe(spritesmith({
    imgName: 'sprites.png',
    imgPath: '/img/sprites.png',
    cssName: 'sprites.styl',
    algorithm: 'binary-tree',
    padding: 2,
    cssFormat: 'stylus',
    cssTemplate: 'src/common/build-css/sprites.styl.tpl',
    cssVarMap: function (sprite) {
      // useful to override template variables
    }
  }));
  var img = spriteData.img.pipe(gulp.dest('img'));
  var css = spriteData.css.pipe(gulp.dest('src/common/build-css'));
  return es.merge(img, css);
});

gulp.task('sprite-classes', function () {
  var spriteData = gulp.src('img/sprites/*.png').pipe(spritesmith({
    imgName: 'sprites.png',
    imgPath: '/img/sprites.png',
    cssName: 'spritesClasses.styl',
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
        throw new gutil.PluginError('spritesmith', {
          message: 'sprite ' + sprite.name + ' is not retina: ' + sprite.width + ' x ' + sprite.height,
          showStack: false
        });
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
  var img = spriteData.img.pipe(gulp.dest('img'));
  var css = spriteData.css.pipe(gulp.dest('src/common'));
  return es.merge(img, css);
});

gulp.task('sprite', ['sprite-imports', 'sprite-classes']);

gulp.task('styles', ['sprite'], function () {
  return gulp.src(stylFiles, {base: './'})
    .pipe(cache(stylesCache))
    .pipe(stylus({use: [nib()], import: ['nib', __dirname + '/src/common/build-css/*.styl']}))
    .pipe(remember(stylesCache))
    .pipe(sortByName())
    .pipe(concat(pkgName + '.css'))
    .pipe(gulpif(!isRelease, gulp.dest(outDir)))
    .pipe(gulpif(isRelease, makeMinCss()));
});

gulp.task('jshint', function () {
  var srcHint = gulp.src(jsFiles)
    .pipe(cache(jsHintSrcCache))
    .pipe(jshint('.jshintrc'));

  var testHint = gulp.src(testJsFiles)
    .pipe(cache(jsHintTestCache))
    .pipe(jshint('test/.jshintrc'));

  return es.merge(srcHint, testHint)
    .pipe(jshint.reporter('jshint-stylish'))
    .pipe(gulpif(isRelease, jshint.reporter('fail')));
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
    .pipe(sortByName())
    .pipe(concat(pkgName + '.js'))
    .pipe(gulpif(!isRelease, gulp.dest(outDir)))
    .pipe(gulpif(isRelease, makeMinJs()));
});

gulp.task('lib-styles', function () {
  return gulp.src(devFiles(libCssFiles))
    .pipe(sortByName())
    .pipe(concat('lib.css'))
    .pipe(gulp.dest(outDir));
});

gulp.task('lib-min-styles', function () {
  return gulp.src(prodFiles(libCssFiles))
    .pipe(gulpif(['**/*.css','!**/*.min.css'], cssmin({keepSpecialComments: 1})))
    .pipe(sortByName())
    .pipe(concat('lib.min.css'))
    .pipe(gulp.dest(outDir));
});

gulp.task('lib-scripts', function () {
  return gulp.src(devFiles(libJsFiles))
    .pipe(sortByName())
    .pipe(concat('lib.js'))
    .pipe(gulp.dest(outDir));
});

gulp.task('lib-min-scripts', function () {
  return gulp.src(prodFiles(libJsFiles))
    .pipe(gulpif(['**/*.js','!**/*.min.js'], uglify()))
    .pipe(sortByName())
    .pipe(concat('lib.min.js'))
    .pipe(gulp.dest(outDir))
});

gulp.task('watch', ['build-dev'], function () {
  livereload.listen();
  gulp.watch(stylFiles, ['styles']).on('change', cacheUpdater(stylesCache));
  gulp.watch(jsFiles, ['scripts']).on('change', cacheUpdater(jsCache, jsHintSrcCache, jsHintTestCache));
  gulp.watch(htmlFiles, ['scripts']).on('change', cacheUpdater(htmlCache));
  gulp.watch(libCssFiles, ['lib-styles']);
  gulp.watch(libJsFiles, ['lib-scripts']);
  gulp.watch(outDir + '/**').on('change', livereload.changed);
});

gulp.task('templates', function () {
  return gulp.src(htmlFiles)
    .pipe(makeTemplates())
    .pipe(sortByName())
    .pipe(concat(pkgName + '-tpl.js'))
    .pipe(gulp.dest(tmpDir));
})

gulp.task('karma', ['templates'], function (done) {
  karma.start({
    frameworks: ['jasmine'],
    files: flatten(
      prodFiles(libJsFiles),
      'lib/angular-mocks/angular-mocks.js',
      tmpDir + '/' + pkgName + '-tpl.js',
      'src/**/*.js',
      'test/unit/**/*.js'
    ),
    reporters: ['dots'],
    colors: true,
    autoWatch: true,
    browsers: ['PhantomJS'],
    captureTimeout: 60000,
    singleRun: true
  }, done);
});

gulp.task('protractor', ['build-release'], function () {
  startProdServer(9080);

  return gulp.src(['test/e2e/**/*.js'])
    .pipe(protractor({
      configFile: "test/protractor.conf.js"
    }))
    .pipe(es.wait(connect.serverClose));
});

gulp.task('test', function (done) {
  runSequence(['karma', 'protractor'], 'clean-tmp', done);
});

gulp.task('assets:compile', function () {
  var assetSrc = ['index.html', 'dist/**', 'img/**', '!img/sprites/**'];

  return gulp.src(assetSrc, { base: '.' })
    .pipe(revall({
      // do not rev these files
      ignore: [ /^\/favicon.ico$/g, /^sprites\//g ],
      prefix: '//d1dwdv9wd966qu.cloudfront.net/'
    }))
    .pipe(gulp.dest('cdn'));
});

gulp.task('assets:rename_index', function () {
  // depends on assets:compile creating the cdn/index file
  return gulp.src('cdn/index.????????.html')
    .pipe(rename('index_cdn.html'))
    .pipe(gulp.dest('./'));
});

gulp.task('assets:publish', function (done) {
  return gulp.src(['cdn/**', '!cdn/index.????????.html'])
    .pipe(awspublish.gzip())
    .pipe(parallelize(publisher.publish({'Cache-Control': 'max-age=315360000, no-transform, public'}), 50))
    .pipe(publisher.cache())
    .pipe(awspublish.reporter());
});

gulp.task('assets', function (done) {
  runSequence('assets:compile', 'assets:rename_index', 'assets:publish', done);
});

gulp.task('build-dev', function (done) {
  runSequence('clean', ['styles', 'scripts', 'lib-styles', 'lib-scripts'], done);
});

gulp.task('build-release', function (done) {
  isRelease = true;
  runSequence('clean', ['styles', 'scripts', 'lib-min-styles', 'lib-min-scripts'], done);
});

// Note: suboptimal use of connect: it already includes livereload (but part of the livereload API is not available)
// Should switch to https://github.com/schickling/gulp-webserver when middleware is supported
gulp.task('server-dev', ['build-dev'], startDevServer);

// Use this task to test the production code locally
gulp.task('prod', ['build-release'], startProdServer);

// This task is the one that should be run by the build script
gulp.task('release', ['build-release', 'karma', 'protractor']);

// Use this task for normal development
gulp.task('default', ['watch', 'server-dev']);
