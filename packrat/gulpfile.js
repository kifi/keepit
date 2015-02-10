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
var jeditor = require('gulp-json-editor');
var lazypipe = require('lazypipe');
var zip = require('gulp-zip');
var shell = require('gulp-shell');
var concat = require('gulp-concat');
var cache = require('gulp-cached');
var remember = require('gulp-remember');
var livereload = require('gulp-livereload');
var gutil = require('gulp-util');
var map = require('./gulp/vinyl-map.js');

var target = 'local';

var outDir = 'out';

var chromeAdapterFiles = ['adapters/chrome/**', '!adapters/chrome/manifest.json'];
var firefoxAdapterFiles = ['adapters/firefox/**', '!adapters/firefox/package.json'];
var sharedAdapterFiles = ['adapters/shared/*.js', 'adapters/shared/*.min.map'];
var resourceFiles = ['icons/url_*.png', 'images/**', 'media/**', 'scripts/**', '!scripts/lib/rwsocket.js'];
var rwsocketScript = 'scripts/lib/rwsocket.js';
var backgroundScripts = [
  'main.js',
  'threadlist.js',
  'lzstring.min.js',
  'scorefilter.js',
  'contact_search_cache.js'
];
var localBackgroundScripts = ['livereload.js']
var tabScripts = ['scripts/**'];
var htmlFiles = 'html/**/*.html';
var styleFiles = 'styles/**/*.*';
var distFiles = outDir + '/**';

var contentScripts = {};
var styleDeps = {};
var scriptDeps = {};
var asap = {};

livereload.options.silent = true;
livereload.options.port = 35719;
var reload = function (file) {
  var match = file.path.match(/\/(.*)$/);
  gutil.log('Changed ' + (match ? gutil.colors.green(match[1]) : file.path));
  // Reload the whole extension (partial reload not supported)
  livereload.changed('*', livereload.options.port);
};

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

function removeMostJsComments(code) {
  return code.toString().replace(/^([^'"\/]*)\s*\/\/.*$/mg, '$1');
}

var chromeInjectionFooter = lazypipe()
  .pipe(function () {
    return gulpif(['scripts/**/*.js', '!**/iframes/**'], map(function (code, filename) {
      var shortName = filename.replace(/^scripts\//, '');
      return code.toString() + "api.injected['" + filename + "']=1;\n//@ sourceURL=http://kifi/" + shortName + '\n';
    }));
  });

gulp.task('clean', function () {
  return gulp.src(outDir, {read: false})
    .pipe(rimraf());
});

gulp.task('copy', function () {

  var chromeAdapters = gulp.src(chromeAdapterFiles, {base: './'})
    .pipe(cache('chrome-adapters'))
    .pipe(rename(function (path) {
      // todo(martin): find a more elegant way to make all files move up two directories
      if (path.dirname === 'adapters' && path.basename === 'chrome') {
        // This is necessary, otherwise an empty 'adapters/chrome' folder is created
        path.dirname = '.';
      }
      path.dirname = path.dirname.replace(/^adapters\/chrome\/?/, '');
    }))
    .pipe(map(removeMostJsComments))
    .pipe(chromeInjectionFooter())
    .pipe(gulp.dest(outDir + '/chrome'));

  var firefoxAdapters = gulp.src(firefoxAdapterFiles, {base: './adapters'})
    .pipe(cache('firefox-adapters'))
    .pipe(map(removeMostJsComments))
    .pipe(gulp.dest(outDir));

  var sharedAdapters = gulp.src(sharedAdapterFiles)
    .pipe(cache('shared-adapters'))
    .pipe(map(removeMostJsComments))
    .pipe(gulp.dest(outDir + '/chrome'))
    .pipe(gulp.dest(outDir + '/firefox/lib'));

  var resources = gulp.src(resourceFiles, { base: './' })
    .pipe(cache('resources'));

  var firefoxResources = resources.pipe(clone())
    .pipe(gulp.dest(outDir + '/firefox/data'));

  var chromeResources = resources.pipe(clone())
    .pipe(rename(function () {})) // very obscure way to make sure filenames use a relative path
    .pipe(chromeInjectionFooter())
    .pipe(gulp.dest(outDir + '/chrome'));

  var chromeIcons = gulp.src('icons/kifi.{48,128,256}.png')
    .pipe(gulp.dest(outDir + '/chrome/icons'));

  var firefoxIcons = gulp.src('icons/kifi.{48,64}.png')
    .pipe(gulp.dest(outDir + '/firefox/data/icons'));

  var rwsocket = gulp.src(rwsocketScript)
    .pipe(cache('rwsocket'))
    .pipe(gulp.dest(outDir + '/chrome'))
    .pipe(gulp.dest(outDir + '/firefox/data/scripts/lib'));

  var scripts = backgroundScripts.concat(target === 'local' ? localBackgroundScripts : []);

  var background = gulp.src(scripts)
    .pipe(cache('background'))
    .pipe(map(removeMostJsComments))
    .pipe(gulp.dest(outDir + '/chrome'))
    .pipe(gulp.dest(outDir + '/firefox/lib'));

  return es.merge(
    chromeAdapters, firefoxAdapters, sharedAdapters,
    chromeResources, firefoxResources,
    chromeIcons, firefoxIcons,
    rwsocket, background);
});

gulp.task('html2js', function () {
  var html2js = function (code, filename) {
    var contents = code.toString()
      .replace(/\s*\n\s*/g, ' ')
      .replace(/'/g, '\\\'')
      .trim();
    var relativeFilename = filename.replace(new RegExp('^' + __dirname + '/'), '');
    if (/^html\/iframes\/.*$/.test(relativeFilename)) {
      return 'document.body.innerHTML=\'' + contents + '\';\n';
    } else {
      var baseFilename = relativeFilename.replace(/\.[^/.]+$/, ""); // strip file extension
      return 'k.templates[\'' + baseFilename + '\']=\'' + contents + '\';\n';
    }
  };

  var common = gulp.src(htmlFiles, {base: './'})
    .pipe(cache('html'))
    .pipe(map(html2js))
    .pipe(rename(function (path) {
      path.extname = '.js';
      path.dirname = 'scripts/' + path.dirname;
    }));

  var firefox = common.pipe(clone())
    .pipe(gulp.dest(outDir + '/firefox/data'));

  var chrome = common.pipe(clone())
    .pipe(chromeInjectionFooter())
    .pipe(gulp.dest(outDir + '/chrome'));

  return es.merge(firefox, chrome);
});

gulp.task('scripts', ['html2js', 'copy']);

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

  function insulate(code) {
    var obj = css.parse(code.toString())
    obj.stylesheet.rules.map(insulateRule);
    return css.stringify(obj);
  }

  function chromify(code) {
    return code.toString().replace(/\/images\//g, 'chrome-extension://__MSG_@@extension_id__/images/');
  }

  var ffBaseUri = 'resource://kifi' + (target === 'dev' ? '-dev' : '') + '-at-42go-dot-com/kifi/data/images/';
  function firefoxify(code) {
    return code.toString().replace(/chrome-extension:\/\/__MSG_@@extension_id__\/images\//g, ffBaseUri);
  }

  function mainStylesOnly(pipefun) {
    return gulpif(RegExp('^(?!' + __dirname + '/styles/(insulate\\.|iframes/))'), map(pipefun));
  }

  return gulp.src(styleFiles, {base: './'})
    .pipe(cache('styles'))
    .pipe(gulpif(/[.]less$/, less()))
    .pipe(mainStylesOnly(insulate))
    .pipe(mainStylesOnly(chromify))
    .pipe(gulp.dest(outDir + '/chrome'))
    .pipe(mainStylesOnly(firefoxify)) // order is important! firefoxify operates on chromified styles (makes code simpler - one unique stream)
    .pipe(gulp.dest(outDir + '/firefox/data'));
});

// Creates meta.js
gulp.task('meta', function () {
  var extractMetadata = function (code, filename) {
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

    return (contentScriptRe ? 'contentScripts ' + relativeFilename + ' ' + asap + ' ' + contentScriptRe : '') + '\n' +
      styleDeps.map(function (styleDep) {
        return 'styleDeps ' + relativeFilename + ' ' + styleDep;
      }).join('\n') + '\n' +
      scriptDeps.map(function (scriptDep) {
        return 'scriptDeps ' + relativeFilename + ' ' + scriptDep;
      }).join('\n') + '\n';
  };

  var buildMetaScript = function (code) {
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
    return JSON.stringify([
      ' [\n  ' + contentScriptItems.join(',\n  ') + ']',
      JSON.stringify(styleDeps, undefined, 2),
      JSON.stringify(scriptDeps, undefined, 2)
    ]);
  };

  var preMeta = gulp.src(tabScripts, {base: './'})
    .pipe(cache('meta'))
    .pipe(map(extractMetadata))
    .pipe(remember('meta'))
    .pipe(concat('meta.js'))
    .pipe(map(buildMetaScript));

  var chromeMeta = preMeta.pipe(clone())
    .pipe(map(function (code) {
      var data = JSON.parse(code.toString());
      return 'meta = {\n  contentScripts:' + data[0] +
        ',\n  styleDeps: ' + data[1] +
        ',\n  scriptDeps: ' + data[2] +
        "};\nif (/^Mac/.test(navigator.platform)) {\n  meta.styleDeps['scripts/keeper_scout.js'] = ['styles/mac.css'];\n}\n";
    }))
    .pipe(gulp.dest(outDir + '/chrome'));

  var firefoxMeta = preMeta.pipe(clone())
    .pipe(map(function (code) {
      var data = JSON.parse(code.toString());
      return 'exports.contentScripts =' + data[0] +
        ';\nexports.styleDeps = ' + data[1] +
        ';\nexports.scriptDeps = ' + data[2] +
        ";\nconst {Ci, Cc} = require('chrome');\nif (/^Mac/.test(Cc['@mozilla.org/network/protocol;1?name=http'].getService(Ci.nsIHttpProtocolHandler).platform)) {\n  exports.styleDeps['scripts/keeper_scout.js'] = ['styles/mac.css'];\n}\n";
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
      if (target === 'local') {
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

gulp.task('build', ['scripts', 'styles', 'meta', 'config']);

gulp.task('config-package-chrome', ['config'], function () {
  return gulp.src(outDir + '/chrome/manifest.json', {base: './'})
    .pipe(jeditor(function (json) {
      if (target !== 'local') {
        json.content_security_policy = json.content_security_policy.replace(/(http|ws):\/\/dev\.ezkeep\.com:\d+\s*/g, '');
        json.content_scripts.forEach(function (scr) {
          scr.matches = scr.matches.filter(function (expr) { return expr.indexOf('://dev.ezkeep.com/') < 0; });
        });
      }
      if (target === 'dev') {
        json.name += ' Dev';
        json.short_name += ' Dev';
        json.update_url = 'https://www.kifi.com/extensions/chrome/kifi-dev.xml';
      }
      return json;
    }))
    .pipe(gulp.dest('.'));
});

gulp.task('zip-chrome', ['build', 'config-package-chrome'], function () {
  return gulp.src(outDir + '/chrome/**')
    .pipe(zip('kifi.zip'))
    .pipe(gulp.dest(outDir));
});

gulp.task('crx-chrome-dev', ['build', 'config-package-chrome'], shell.task([[
  'cp icons/dev/kifi.{48,128,256}.png out/chrome/icons/',
  '/Applications/Google\\ Chrome.app/Contents/MacOS/Google\\ Chrome' +
  ' --pack-extension=out/chrome --pack-extension-key=kifi-dev-chrome.pem > /dev/null',
  'mv out/chrome.crx out/kifi-dev.crx',
  'echo $\'<?xml version="1.0" encoding="UTF-8"?>\\n<gupdate xmlns="http://www.google.com/update2/response" protocol="2.0">\\n  <app appid="ddepcfcogoamilbllhdmlojoefkjdofi">\\n    <updatecheck codebase="https://www.kifi.com/extensions/chrome/kifi-dev.crx" version="\'$(grep \'"version"\' out/chrome/manifest.json | cut -d\\" -f4)$\'" />\\n  </app>\\n</gupdate>\' > out/kifi-dev.xml'
].join(' && ')]));

gulp.task('xpi-firefox', ['build', 'config'], function () {
  return gulp.src(outDir + '/firefox/package.json', {base: './'})
    .pipe(jeditor(function (json) {
      if (target === 'dev') {
        json.id = json.id.replace('@', '-dev@');
        json.name += '-dev';
        json.title += ' Dev';
      }
      return json;
    }))
    .pipe(gulp.dest('.'))
    .pipe(shell([
      // TODO: verify cfx version before using it
      // cfxver=$(cfx --version)
      // if [ "$cfxver" != "Add-on SDK 1.17 (12f7d53e8b5fc015a15fa4a30fa588e81e9e9b2e)" ]; then
      //   echo "$cfxver"$'\n'"Looks like you need to download the latest Firefox Addon SDK."
      //   echo "https://addons.mozilla.org/en-US/developers/builder"
      //   exit 1
      // fi
      (target === 'dev' ? 'cp icons/dev/kifi.??.png out/firefox/data/icons/ && ' : '') + '\
      cd ' + outDir + ' && \
      cfx xpi --pkgdir=firefox \
        --update-link=https://www.kifi.com/extensions/firefox/kifi' + (target === 'dev' ? '-dev' : '') + '.xpi \
        --update-url=https://www.kifi.com/extensions/firefox/kifi' + (target === 'dev' ? '-dev' : '') + '.update.rdf && \
      cd - > /dev/null'
    ]));
});

gulp.task('watch', function () {
  livereload.listen(livereload.options.port);
  gulp.watch(
    [].concat(
      chromeAdapterFiles,
      firefoxAdapterFiles,
      sharedAdapterFiles,
      resourceFiles,
      rwsocketScript,
      backgroundScripts,
      localBackgroundScripts,
      htmlFiles),
    ['scripts']);
  gulp.watch(styleFiles, ['styles']);
  gulp.watch(tabScripts, ['meta']);
  gulp.watch(distFiles).on('change', reload);
});

gulp.task('package', function () {
  target = 'prod';
  runSequence('clean', ['zip-chrome', 'xpi-firefox']);
});

gulp.task('package-dev', function () {
  target = 'dev';
  runSequence('clean', ['crx-chrome-dev', 'xpi-firefox']);
});

gulp.task('default', function () {
  runSequence('clean', 'build', 'watch');
});
