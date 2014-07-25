var gulp = require('gulp');
var stylus = require('gulp-stylus');
var rimraf = require('gulp-rimraf');
var runSequence = require('run-sequence');
var map = require('vinyl-map');
var concat = require('gulp-concat');
var rename = require('gulp-rename');
var cssmin = require('gulp-cssmin');
var nib = require('nib');
var watch = require('gulp-watch');
var lazypipe = require('lazypipe');
var plumber = require('gulp-plumber');
var livereload = require('gulp-livereload');
var es = require('event-stream');
var cache = require('gulp-cached');
var remember = require('gulp-remember');

var outDir = 'dist';

var stylFiles = ['src/**/*.styl', '!src/common/build-css/*.styl'];

var stylesCache = 'styles';

var cacheUpdater = function (cacheName) {
  return function (event) {
    if (event.type === 'deleted') {
      delete cache.caches[cacheName][event.path];
      remember.forget(cacheName, event.path);
    }
  }
}

gulp.task('clean', function () {
  return gulp.src(outDir, {read: false})
    .pipe(rimraf());
});

gulp.task('styles', function () {
  return gulp.src(stylFiles, {base: './'})
    .pipe(cache(stylesCache))
    .pipe(stylus({use: [nib()], import: ['nib', __dirname + '/src/common/build-css/*.styl']}))
    .pipe(remember(stylesCache))
    .pipe(concat('kifi.css'))
    .pipe(gulp.dest(outDir))
    .pipe(rename({suffix: '.min'}))
    .pipe(gulp.dest(outDir));
});

gulp.task('watch', function () {
  livereload.listen();
  gulp.watch(stylFiles, ['styles']).on('change', cacheUpdater(stylesCache));
  gulp.watch(outDir + '/kifi.css').on('change', livereload.changed);
});

gulp.task('default', function () {
  runSequence('clean', ['watch', 'styles']);
});
