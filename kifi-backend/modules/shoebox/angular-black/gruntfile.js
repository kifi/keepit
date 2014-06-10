'use strict';

// http://www.jshint.com/docs/
/* jshint node: true */

// https://github.com/lodash/lodash/#installation--usage
// http://lodash.com/docs
//var _ = require('lodash');
var jsonminify = require('jsonminify');

module.exports = function (grunt) {
  // http://gruntjs.com/api/grunt
  // http://gruntjs.com/api/grunt.template

  function readJSON(path) {
    return JSON.parse(jsonminify(grunt.file.read(path)));
  }

  function notATest(path) {
    return !/\.(spec|scenario)\.js$/.test(path);
  }

  // Project Configuration
  grunt.initConfig({
    // http://gruntjs.com/api/grunt.config
    pkg: grunt.file.readJSON('package.json'),
    banner: grunt.file.read('banner.txt'),
    path: {
      js: [
        'gruntfile.js',
        '<%= path.client.js %>',
        '<%= path.test.js %>'
      ],
      config: {
        dir: 'config'
      },
      client: {
        lib: 'lib',
        libCss: [
          'lib/normalize-css/normalize.css',
          'managed-lib/bootstrap/bootstrap.css',
          'managed-lib/jquery-ui-1.10.4.custom/css/smoothness/jquery-ui-1.10.4.custom.css',
          'managed-lib/pace/pace.css'
        ],
        libMinCss: [
          'lib/normalize-css/normalize.css',
          'managed-lib/bootstrap/bootstrap.css',
          'managed-lib/jquery-ui-1.10.4.custom/css/smoothness/jquery-ui-1.10.4.custom.min.css'
        ],
        libJs: [
          'lib/lodash/dist/lodash.js',
          'lib/underscore.string/lib/underscore.string.js',
          'lib/jquery/dist/jquery.js',
          'lib/angular/angular.js',
          'lib/angular-cookies/angular-cookies.js',
          'lib/angular-resource/angular-resource.js',
          'lib/angular-sanitize/angular-sanitize.js',
          'lib/angular-route/angular-route.js',
          //'lib/angular-ui-router/release/angular-ui-router.js',
          'lib/angular-animate/angular-animate.js',
          'lib/jquery.mousewheel/jquery.mousewheel.js',
          'lib/antiscroll/antiscroll.js',
          //'lib/angular-antiscroll/angular-antiscroll.js',
          'lib/angular-smart-scroll/dist/angular-smart-scroll.js',
          'lib/moment/moment.js',
          'lib/angular-moment/angular-moment.js',
          'managed-lib/bootstrap/ui-bootstrap-custom-tpls-0.10.0.js',
          'lib/angular-facebook-api/dist/angular-facebook-api.js',
          'managed-lib/jquery-ui-1.10.4.custom/js/jquery-ui-1.10.4.custom.js',
          'managed-lib/ui-slider/slider.js',
          'lib/angulartics/dist/angulartics.min.js'
        ],
        libMinJs: [
          'lib/lodash/dist/lodash.min.js',
          'lib/underscore.string/dist/underscore.string.min.js',
          'lib/jquery/dist/jquery.min.js',
          'lib/angular/angular.min.js',
          'lib/angular-cookies/angular-cookies.min.js',
          'lib/angular-resource/angular-resource.min.js',
          'lib/angular-sanitize/angular-sanitize.min.js',
          'lib/angular-route/angular-route.min.js',
          //'lib/angular-ui-router/release/angular-ui-router.min.js',
          'lib/angular-animate/angular-animate.min.js',
          //'lib/angular-bootstrap/ui-bootstrap-tpls.min.js',
          'lib/jquery.mousewheel/jquery.mousewheel.js',
          'lib/antiscroll/antiscroll.js',
          //'lib/angular-antiscroll/angular-antiscroll.js',
          'lib/angular-smart-scroll/dist/angular-smart-scroll.min.js',
          'lib/moment/min/moment.min.js',
          'lib/angular-moment/angular-moment.min.js',
          'managed-lib/bootstrap/ui-bootstrap-custom-tpls-0.10.0.min.js',
          'lib/angular-facebook-api/dist/angular-facebook-api.min.js',
          'managed-lib/jquery-ui-1.10.4.custom/js/jquery-ui-1.10.4.custom.min.js',
          'managed-lib/ui-slider/slider.js',
          'lib/angulartics/dist/angulartics.min.js'
        ],
        src: 'src',
        common: 'src/common/build-css',
        commonStyl: 'src/common/build-css/*.styl',
        assets: 'assets',
        js: 'src/**/*.js',
        styl: 'src/**/*.styl',
        html: 'src/**/*.tpl.html',
        css: ['<%= path.client.styl %>', '!<%= path.client.commonStyl %>'],
        jshintrc: '.jshintrc'
      },
      dist: {
        dir: 'dist',
        css: 'dist/<%= pkg.name %>.css',
        minCss: 'dist/<%= pkg.name %>.min.css',
        libCss: 'dist/lib.css',
        libMinCss: 'dist/lib.min.css',
        js: 'dist/<%= pkg.name %>.js',
        minJs: 'dist/<%= pkg.name %>.min.js',
        libJs: 'dist/lib.js',
        libMinJs: 'dist/lib.min.js',
        minTwiceJs: 'dist/lib.twice.min.js',
        tpl: 'dist/<%= pkg.name %>-tpl.js'
        //tplMin: 'dist/<%= pkg.name %>-tpl.min.js'
      },
      test: {
        js: 'test/**/*.js',
        coverage: 'test/coverage',
        specs: 'src/**/*.spec.js',
        scenarios: 'src/**/*.scenario.js',
        client: ['<%= path.test.specs %>', '<%= path.test.scenarios %>'],
        karma: 'test',
        karmaConfig: 'test/karma.conf.js',
        jshintrc: 'test/.jshintrc'
      }
    },
    stylus: {
      // https://github.com/gruntjs/grunt-contrib-stylus#options
      dev: {
        options: {
          compress: false,
          urlfunc: 'embedurl',
          paths: ['<%= path.client.common %>'],
          'import': [
            'nib',
            'constants',
            'common',
            'sprites'
          ]
        },
        files: {
          '<%= path.dist.css %>': '<%= path.client.css %>'
        }
      }
    },
    cssmin: {
      // https://github.com/GoalSmashers/clean-css#how-to-use-clean-css-programmatically
      // https://github.com/gruntjs/grunt-contrib-cssmin#options
      options: {
        keepSpecialComments: 1
      },
      dist: {
        src: ['<%= path.dist.css %>'],
        dest: '<%= path.dist.minCss %>'
      },
      lib: {
        src: ['<%= path.dist.libCss %>'],
        dest: '<%= path.dist.libMinCss %>'
      }
    },
    html2js: {
      // https://github.com/gruntjs/grunt-contrib-htmlmin#options
      // https://github.com/karlgoldstein/grunt-html2js
      options: {
        htmlmin: {
          collapseBooleanAttributes: true,
          collapseWhitespace: true,
          removeAttributeQuotes: false,
          removeComments: true,
          removeCommentsFromCDATA: true,
          removeCDATASectionsFromCDATA: true,
          removeEmptyAttributes: false,
          removeEmptyElements: false,
          removeRedundantAttributes: false,
          removeScriptTypeAttributes: false,
          removeStyleLinkTypeAttributes: false,
          removeOptionalTags: false,
          useShortDoctype: true
        },
        quoteChar: '\'',
        useStrict: true
      },
      kifi: {
        base: '<%= path.client.src %>',
        src: ['<%= path.client.html %>'],
        dest: '<%= path.dist.tpl %>',
        module: 'kifi.templates'
      }
    },
    clean: {
      // https://github.com/gruntjs/grunt-contrib-clean#options
      dist: {
        src: ['<%= path.dist.dir %>/*']
      },
      test: {
        src: ['<%= path.test.coverage %>/*']
      },
      library: {
        src: ['<%= path.client.lib %>/*']
      }
    },
    copy: {
      // https://github.com/gruntjs/grunt-contrib-copy#options
      assets: {
        cwd: '<%= path.client.assets %>',
        src: '**',
        dest: '<%= path.dist.dir %>',
        expand: true
      }
    },
    jshint: {
      // https://github.com/gruntjs/grunt-contrib-jshint#options
      // http://www.jshint.com/docs/options
      client: {
        options: readJSON('.jshintrc'),
        src: ['<%= path.client.js %>'],
        filter: notATest
      },
      test: {
        options: readJSON('test/.jshintrc'),
        src: ['<%= path.test.client %>']
      }
    },
    uglify: {
      // https://github.com/mishoo/UglifyJS2#usage
      // https://github.com/gruntjs/grunt-contrib-uglify#options
      dist: {
        src: ['<%= concat.dist.src %>'],
        dest: '<%= path.dist.minJs %>'
      },
      libMin: {
        src: ['<%= path.dist.libMinJs %>'],
        dest: '<%= path.dist.minTwiceJs %>'
      }
      /*
      tpl: {
        src: ['<%= path.dist.tpl %>'],
        dest: '<%= path.dist.tplMin %>'
      }
      */
    },
    concat: {
      // https://github.com/gruntjs/grunt-contrib-concat#options
      libCss: {
        src: ['<%= path.client.libCss %>'],
        dest: '<%= path.dist.libCss %>'
      },
      libJs: {
        src: ['<%= path.client.libJs %>'],
        dest: '<%= path.dist.libJs %>'
      },
      libMinJs: {
        src: ['<%= path.client.libMinJs %>'],
        dest: '<%= path.dist.libMinJs %>'
      },
      dist: {
        options: {
          banner: '<%= banner %>'
        },
        src: ['<%= path.client.js %>', '<%= path.dist.tpl %>'],
        filter: notATest,
        dest: '<%= path.dist.js %>'
      }
    },
    karma: {
      // https://github.com/karma-runner/grunt-karma#config
      options: {
        configFile: '<%= path.test.karmaConfig %>'
      },
      unit: {},
      watch: {
        singleRun: false,
        autoWatch: true
      }
    },
    sprite:{
      // https://github.com/Ensighten/grunt-spritesmith
      base2x: {
        src: 'img/sprites/*.png',
        destImg: 'img/sprites.png',
        destCSS: 'src/common/build-css/sprites.styl',
        imgPath: '/img/sprites.png',
        algorithm: 'binary-tree',
        padding: 2,
        cssFormat: 'stylus',
        cssVarMap: function (sprite) {
          // useful to override template variables
        },
        cssTemplate: 'src/common/build-css/sprites.styl.tpl'
      },
      baseCss2x: {
        src: 'img/sprites/*.png',
        destImg: 'img/sprites.png',
        destCSS: 'src/common/spritesClasses.styl',
        imgPath: '/img/sprites.png',
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

          if (pseudoClasses.indexOf(modifier) !== -1) {
            cssSelector += '.sprite-' + sprite.name + ',\n.sprite-' + root + ':' + modifier + '\n  sprite2x($' + sprite.name + ')\n';
          } else if (modifier === 'default') {
            cssSelector += '.sprite-' + root + '\n  sprite2x($' + sprite.name + ')\n';
          } else {
            cssSelector += '.sprite-' + sprite.name + '\n  sprite2x($' + sprite.name + ')\n';
          }
          sprite.cssSelector = cssSelector;
        }
      }
    },
    env: {
      // https://github.com/jsoverson/grunt-env#configuration
      dev: {
        NODE_ENV: 'development'
      },
      test: {
        NODE_ENV: 'test'
      },
      release: {
        NODE_ENV: 'production'
      },
      heroku: {
        NODE_ENV: 'production'
      }
    },
    watch: {
      options: {
        debounceDelay: 1000
      },
      // https://github.com/gruntjs/grunt-contrib-watch#settings
      stylusDev: {
        files: ['<%= path.client.css %>'],
        tasks: ['stylus:dev']
      },
      cssMinDist: {
        files: ['<%= cssmin.dist.src %>'],
        tasks: ['cssmin:dist']
      },
      cssMinLib: {
        files: ['<%= cssmin.lib.src %>'],
        tasks: ['cssmin:lib']
      },
      html2jsTpl: {
        files: ['<%= html2js.kifi.src %>'],
        tasks: ['html2js:kifi']
      },
      copy: {
        files: ['<%= path.client.assets %>/**'],
        tasks: ['copy:assets']
      },
      jshintClient: {
        options: {
          event: ['added', 'changed']
        },
        files: ['<%= jshint.client.src %>'],
        tasks: ['jshint:client']
      },
      jshintTest: {
        options: {
          event: ['added', 'changed']
        },
        files: ['<%= jshint.test.src %>'],
        tasks: ['jshint:test']
      },
      uglifyDist: {
        files: ['<%= uglify.dist.src %>'],
        tasks: ['uglify:dist']
      },
      /*
      uglifyTpl: {
        files: ['<%= uglify.tpl.src %>'],
        tasks: ['uglify:tpl']
      },
      */
      concatLibJs: {
        files: ['<%= concat.libJs.src %>'],
        tasks: ['concat:libJs']
      },
      concatLibMinJs: {
        files: ['<%= concat.libMinJs.src %>'],
        tasks: ['concat:libMinJs']
      },
      concatDist: {
        files: ['<%= concat.dist.src %>'],
        tasks: ['concat:dist']
      },
      livereload: {
        options: {
          livereload: 8079
        },
        files: ['index.html', '<%= path.dist.dir %>/**/*']
      }
    }
  });

  //Load NPM tasks
  grunt.loadNpmTasks('grunt-contrib-clean');
  grunt.loadNpmTasks('grunt-contrib-copy');
  grunt.loadNpmTasks('grunt-contrib-concat');
  grunt.loadNpmTasks('grunt-contrib-watch');
  grunt.loadNpmTasks('grunt-contrib-jshint');
  grunt.loadNpmTasks('grunt-contrib-uglify');
  grunt.loadNpmTasks('grunt-contrib-stylus');
  grunt.loadNpmTasks('grunt-contrib-cssmin');
  grunt.loadNpmTasks('grunt-karma');
  grunt.loadNpmTasks('grunt-spritesmith');
  grunt.loadNpmTasks('grunt-env');
  grunt.loadNpmTasks('grunt-html2js');

  //Making grunt default to force in order not to break the project.
  grunt.option('force', true);

  // http://gruntjs.com/creating-tasks

  // Default task(s).
  grunt.registerTask('default', [
    'dev',
    'watch'
  ]);

  // Development task
  grunt.registerTask('dev', [
    'env:dev',
    'clean:dist',
    'clean:test',
    'stylus:dev',
    'html2js',
    'copy',
    'jshint',
    'uglify:dist',
    'concat',
    'cssmin'
    //'uglify:libMin'
  ]);

  // Test task.
  grunt.registerTask('test', [
    'env:test',
    'karma:unit'
  ]);

  // Test task.
  grunt.registerTask('tkarma', [
    'env:test',
    'karma:unit'
  ]);

  // Release task
  grunt.registerTask('release', [
    'env:release',
    'clean:dist',
    'clean:test',
    'stylus:dev',
    'html2js',
    'copy',
    'jshint',
    'uglify:dist',
    'concat',
    'cssmin',
    //'uglify:libMin',
    'karma:unit'
  ]);
};
