module.exports = function (grunt) {
	'use strict';

	/*
        Grunt installation:
        -------------------
            npm install -g grunt-cli
            npm install -g grunt-init
            npm init (creates a `package.json` file)

        Project Dependencies:
        ---------------------
            npm install grunt --save-dev
            npm install grunt-contrib-watch --save-dev
            npm install grunt-contrib-jshint --save-dev
            npm install grunt-contrib-less --save-dev
            npm install grunt-contrib-uglify --save-dev
            npm install grunt-contrib-requirejs --save-dev
            npm install grunt-contrib-imagemin --save-dev
            npm install load-grunt-tasks --save-dev
            npm install time-grunt --save-dev

        Simple Dependency Install:
        --------------------------
            npm install (from the same root directory as the `package.json` file)

        Gem Dependencies:
        -----------------
            gem install image_optim
    */

	// Displays the elapsed execution time of grunt tasks
	require('time-grunt')(grunt);

	// Load NPM Tasks
	require('load-grunt-tasks')(grunt, ['grunt-*']);

	// Project configuration.
	grunt.initConfig({

		// Store your Package file so you can reference its specific data whenever necessary
		pkg: grunt.file.readJSON('package.json'),

		src: {
			kifi: [
				'js/**/*.js',
				'!js/**/*.min.js',
				'!js/**/jquery*',
				'!js/handlebars*',
				'!js/tempo*',
				'!js/antiscroll*'
			],
			minify: [
				'js/util.js',
				'js/scorefilter.js',
				'js/kifi.js',
				'js/main.js',
				'js/track.js'
			]
		},

		jshint: {
			/*
                Note:
                In case there is a /release/ directory found, we don't want to lint that
                so we use the ! (bang) operator to ignore the specified directory
            */
			files: ['Gruntfile.js', '<%= src.kifi %>'],
			options: {
				curly: true,
				eqeqeq: true,
				immed: true,
				latedef: true,
				newcap: true,
				noarg: true,
				sub: true,
				undef: true,
				boss: true,
				eqnull: true,
				browser: true,

				globals: {
					// AMD
					module: true,
					require: true,
					requirejs: true,
					define: true,

					// Environments
					console: true,

					// General Purpose Libraries
					$: true,
					jQuery: true
				}
			}
		},

		uglify: {
			options: {
				// mangle:
				// compress:
				// beautify:
				// report:
				// sourceMap:
				// sourceMapRoot:
				// sourceMapIn:
				// sourceMappingURL:
				// sourceMapPrefix:
				// wrap:
				// exportAll:
				// preserveComments:
				// banner:
				// footer:
				banner: '/*! <%= pkg.name %> | <%= pkg.version %> | <%= grunt.template.today("yyyy-mm-dd") %> /\n'
			},
			dist: {
				src: '<%= src.minify %>',
				dest: 'dist/<%= pkg.name %>.min.js'
			}
		},

		less: {
			statics: {
				options: {
					paths: ['css'],
					compress: true,
					cleancss: true
				},
				files: {
					'static/css/landing.css': 'static/css/landing.less'
				}
			}
		},

		requirejs: {
			compile: {
				options: {
					baseUrl: './app',
					mainConfigFile: './app/main.js',
					dir: './app/release/',
					fileExclusionRegExp: /^\.|node_modules|Gruntfile|\.md|package.json/,
					// optimize: 'none',
					modules: [
						{
							name: 'main'
							// include: ['module'],
							// exclude: ['module']
                        }
					]
				}
			}
		},

		// `optimizationLevel` is only applied to PNG files (not JPG)
		imagemin: {
			png: {
				options: {
					optimizationLevel: 7
				},
				files: [
					{
						expand: true,
						cwd: './img/',
						src: ['**/*.png'],
						dest: './img/compressed/',
						ext: '.png'
                    }
				]
			},
			jpg: {
				options: {
					progressive: true
				},
				files: [
					{
						expand: true,
						cwd: './img/',
						src: ['**/*.jpg'],
						dest: './img/compressed/',
						ext: '.jpg'
                    }
				]
			}
		},

		// Run: `grunt watch` from command line for this section to take effect
		watch: {
			files: ['<%= jshint.files %>'],
			tasks: 'default'
		}

	});

	// Default Task
	grunt.registerTask('default', ['jshint']);

	// Release Task
	grunt.registerTask('build', ['jshint', 'uglify', 'imagemin']);

	// Release Task
	grunt.registerTask('release', ['jshint', 'requirejs', 'imagemin']);

	/*
        Notes:

        When registering a new Task we can also pass in any other registered Tasks.
        e.g. grunt.registerTask('release', 'default requirejs'); // when running this task we also run the 'default' Task
    */

};
