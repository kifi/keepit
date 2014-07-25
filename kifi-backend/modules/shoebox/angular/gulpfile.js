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

var outDir = 'dist';
var tmpDir = 'tmp';

var stylFiles = ['src/**/*.styl', '!src/common/build-css/*.styl'];
var cssTmpFiles = [tmpDir + '/**/*.css'];

gulp.task('clean', function () {
  return gulp.src([outDir, tmpDir], {read: false})
    .pipe(rimraf());
});

gulp.task('styles-compile', function () {
  return gulp.src(stylFiles, {base: './'})
    .pipe(cache('styles-compile'))
    .pipe(stylus({use: [nib()], import: ['nib', __dirname + '/src/common/build-css/*.styl']}))
    .pipe(gulp.dest(tmpDir));
});

gulp.task('styles-concat', ['styles-compile'], function () {
  return gulp.src(tmpDir + '/src/**/*.css')
    .pipe(concat('kifi.css'))
    .pipe(gulp.dest(outDir));
});

gulp.task('styles-minify', ['styles-concat'], function () {
  return gulp.src(outDir + '/kifi.css')
    .pipe(cssmin())
    .pipe(rename({suffix: '.min'}))
    .pipe(gulp.dest(outDir));
});

gulp.task('watch', function () {
  livereload.listen();
  gulp.watch(stylFiles, ['styles-minify']);
  gulp.watch(outDir + '/kifi.css').on('change', livereload.changed);
});

gulp.task('default', function () {
  runSequence('clean', ['watch', 'styles-minify']);
});
