var gulp = require('gulp');
var path = require('path');
var rimraf = require('gulp-rimraf');
var rename = require('gulp-rename');
var less = require('gulp-less');
var runSequence = require('run-sequence');
var gulpif = require('gulp-if');
var clone = require('gulp-clone');
var css = require('css');
var es = require('event-stream');
var fs = require('fs');
var plist = require('plist');
var jsvalidate = require('gulp-jsvalidate');
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
var filenames = require('gulp-filenames');
var explicitGlobals = require('explicit-globals');

var target = 'local';
var listed = false;

var outDir = 'out';

var chromeAdapterFiles = ['adapters/chrome/**', '!adapters/chrome/manifest.json'];
var firefoxAdapterFiles = ['adapters/firefox/**', '!adapters/firefox/package.json'];
var safariAdapterFiles = ['adapters/safari/**', '!adapters/safari/Info.plist.json'];
var sharedAdapterFiles = ['adapters/shared/*.js', 'adapters/shared/*.min.map'];
var resourceFiles = ['icons/url_*.png', 'images/**', 'media/**', 'scripts/**', '!scripts/lib/rwsocket.js'];
var firefoxScriptModuleFiles = ['**/scripts/**/*.js', '!scripts/lib/jquery.js', '!scripts/lib/mustache.js', '!scripts/lib/underscore.js'];
var rwsocketScript = 'scripts/lib/rwsocket.js';
var backgroundScripts = [
  'main.js',
  'threadlist.js',
  'lzstring.min.js',
  'scorefilter.js',
  'contact_search_cache.js'
];
var localBackgroundScripts = ['livereload.js'];
var tabScripts = ['scripts/**'];
var htmlFiles = 'html/**/*.html';
var styleFiles = 'styles/**/*.*';
var distFiles = outDir + '/**';

var validateScripts = ['scripts/**', './*.js'];

var contentScripts = {};
var styleDeps = {};
var scriptDeps = {};
var asap = {};

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

var injectionFooter = lazypipe()
  .pipe(function () {
    return gulpif(['scripts/**/*.js'], map(function (code, filename) {
      var shortName = filename.replace(/^scripts\//, '');
      return code.toString() + "api.injected['" + filename + "']=1;\n//@ sourceURL=http://kifi/" + shortName + '\n';
    }));
  });

var firefoxExplicitGlobals = lazypipe()
  .pipe(function () {
    return gulpif(['**/scripts/**/*.js', '!**/lib/**'], map(function (code, filename) {
      return explicitGlobals(code.toString());
    }));
  });

function wrapWithModuleCode(code, filename, relative) {
  if (relative) {
    filename = path.relative(relative, filename);
  }
  var top = '\
  \nvar init_module = init_module || {}; \
  \ninit_module[\'' + filename + '\'] = function () { \
  \ninit_module[\'' + filename + '\'] = function () {}; \
  \n';
  var bottom = '\n};\n'

  return top + code.toString() + bottom;
}

var firefoxInjectionModule = lazypipe()
  .pipe(function () {
    return gulpif(firefoxScriptModuleFiles, map(function (code, filename) {
      if (code.toString().indexOf('api.identify(') !== -1) {
        return code;
      } else {
        return wrapWithModuleCode(code, filename);
      }
    }));
  });

var firefoxAdapterInjectionModule = lazypipe()
  .pipe(function () {
    return gulpif(firefoxScriptModuleFiles, map(function (code, filename) {
      var codeString = code.toString();
      if (codeString.indexOf('@module') !== -1) {
        return wrapWithModuleCode(explicitGlobals(codeString), filename, 'adapters/firefox/data');
      } else {
        return code;
      }
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
    .pipe(injectionFooter())
    .pipe(gulp.dest(outDir + '/chrome'));

  var safariAdapters = gulp.src(safariAdapterFiles, {base: './'})
    .pipe(cache('safari-adapters'))
    .pipe(rename(function (path) {
      // todo(martin): find a more elegant way to make all files move up two directories
      if (path.dirname === 'adapters' && path.basename === 'safari') {
        // This is necessary, otherwise an empty 'adapters/chrome' folder is created
        path.dirname = '.';
      }
      path.dirname = path.dirname.replace(/^adapters\/safari\/?/, 'kifi.safariextension/');
    }))
    .pipe(map(removeMostJsComments))
    .pipe(injectionFooter())
    .pipe(gulp.dest(outDir));

  var firefoxAdapters = gulp.src(firefoxAdapterFiles, {base: './adapters'})
    .pipe(cache('firefox-adapters'))
    .pipe(rename(function () {}))
    .pipe(firefoxAdapterInjectionModule())
    .pipe(map(removeMostJsComments))
    .pipe(gulp.dest(outDir))
    .pipe(filenames('firefox-deps'));

  var sharedAdapters = gulp.src(sharedAdapterFiles)
    .pipe(cache('shared-adapters'))
    .pipe(map(removeMostJsComments))
    .pipe(gulp.dest(outDir + '/chrome'))
    .pipe(gulp.dest(outDir + '/kifi.safariextension'))
    .pipe(gulp.dest(outDir + '/firefox/lib'));

  var resources = gulp.src(resourceFiles, { base: './' })
    .pipe(cache('resources'));


  var firefoxResources = resources.pipe(clone())
    .pipe(rename(function () {}))
    .pipe(firefoxExplicitGlobals())
    .pipe(firefoxInjectionModule())
    .pipe(gulp.dest(outDir + '/firefox/data'))
    .pipe(filenames('firefox-deps'));

  var chromeResources = resources.pipe(clone())
    .pipe(rename(function () {})) // very obscure way to make sure filenames use a relative path
    .pipe(injectionFooter())
    .pipe(gulp.dest(outDir + '/chrome'));

  var safariResources = resources.pipe(clone())
    .pipe(rename(function () {}))
    .pipe(injectionFooter())
    .pipe(gulp.dest(outDir + '/kifi.safariextension'));

  var chromeIcons = gulp.src('icons/kifi.{48,128,256}.png')
    .pipe(gulp.dest(outDir + '/chrome/icons'));

  var safariIcons = gulp.src('icons/kifi.{48,128,256}.png')
    .pipe(gulp.dest(outDir + '/kifi.safariextension/icons'));

  var firefoxIcons = gulp.src('icons/kifi.{48,64}.png')
    .pipe(gulp.dest(outDir + '/firefox/data/icons'));

  var rwsocket = gulp.src(rwsocketScript)
    .pipe(cache('rwsocket'))
    .pipe(gulp.dest(outDir + '/chrome'))
    .pipe(gulp.dest(outDir + '/kifi.safariextension'))
    .pipe(gulp.dest(outDir + '/firefox/data/scripts/lib'));

  var scripts = backgroundScripts.concat(target === 'local' ? localBackgroundScripts : []);

  var background = gulp.src(scripts)
    .pipe(cache('background'))
    .pipe(map(removeMostJsComments))
    .pipe(gulp.dest(outDir + '/chrome'))
    .pipe(gulp.dest(outDir + '/kifi.safariextension'))
    .pipe(gulp.dest(outDir + '/firefox/lib'))
    .pipe(filenames('firefox-deps'));

  return es.merge(
    chromeAdapters, safariAdapters, firefoxAdapters, sharedAdapters,
    chromeResources, safariResources, firefoxResources,
    chromeIcons, safariIcons, firefoxIcons,
    rwsocket, background);
});

gulp.task('html2js', function () {
  var html2js = function (code, filename) {
    var contents = code.toString()
      .replace(/\s*\n\s*/g, ' ')
      .replace(/'/g, '\\\'')
      .trim();
    var relativeFilename = filename.replace(new RegExp('^' + __dirname + '/'), '');
    var baseFilename = relativeFilename.replace(/\.[^/.]+$/, ""); // strip file extension
    return 'k.templates[\'' + baseFilename + '\']=\'' + contents + '\';\n';
  };

  var common = gulp.src(htmlFiles, {base: './'})
    .pipe(cache('html'))
    .pipe(map(html2js))
    .pipe(rename(function (path) {
      path.extname = '.js';
      path.dirname = 'scripts/' + path.dirname;
    }))
    .pipe(filenames('firefox-deps'));

  var firefox = common.pipe(clone())
    .pipe(firefoxExplicitGlobals())
    .pipe(firefoxInjectionModule())
    .pipe(gulp.dest(outDir + '/firefox/data'))

  var chrome = common.pipe(clone())
    .pipe(injectionFooter())
    .pipe(gulp.dest(outDir + '/chrome'));

  var safari = common.pipe(clone())
    .pipe(injectionFooter())
    .pipe(gulp.dest(outDir + '/kifi.safariextension'));


  return es.merge(firefox, chrome, safari);
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
    return code.toString().replace(/\/images\//g, ffBaseUri);
  }

  var someHash = 'deadbeef';
  var safariBaseUri = 'safari-extension://com.fortytwo.kifi-V4GCE6T8A5/' + someHash + '/';
  function safarify(code) {
    return code.toString().replace(/\/images\//g, safariBaseUri);
  }

  function mainStylesOnly(pipefun) {
    return gulpif(RegExp('^(?!' + __dirname + '/styles/(insulate\\.))'), map(pipefun));
  }

  var stylePipe = gulp.src(styleFiles, {base: './'})
    .pipe(cache('styles'))
    .pipe(gulpif(/[.]less$/, less()))
    .pipe(mainStylesOnly(insulate));

  stylePipe
    .pipe(clone())
    .pipe(mainStylesOnly(chromify))
    .pipe(gulp.dest(outDir + '/chrome'));

  stylePipe
    .pipe(clone())
    .pipe(mainStylesOnly(firefoxify))
    .pipe(gulp.dest(outDir + '/firefox/data'));

  stylePipe
    .pipe(clone())
    .pipe(mainStylesOnly(safarify))
    .pipe(gulp.dest(outDir + '/kifi.safariextension'));

  return stylePipe;
});


gulp.task('jsvalidate', function () {
  return gulp.src(validateScripts)
    .pipe(cache('jsvalidate'))
    .pipe(jsvalidate());
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
        contentScriptItems.push({
          data: [ filename, regex, asap ],
          string: '["' + filename + '", ' + regex + ', ' + asap + ']'
        });
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

    var flatScriptDeps = [
      rwsocketScript
    ].concat(filenames.get('firefox-deps')
    .filter(function (dep) {
      return (
        dep.slice(-3) === '.js' && // only script files
        ~dep.indexOf('scripts/') && // make sure it's in the scripts folder
        !~dep.indexOf('firefox/lib/') && // but not in the backend-script lib folder
        contentScriptItems.map(function (csi) { return csi.data[0]; }).indexOf(dep) === -1 // and don't add top-level scripts to the deps
      );
    })
    .map(function (dep) {
      return dep.replace(/firefox\/data\//g, '');
    }));

    return JSON.stringify([
      ' [\n  ' + contentScriptItems.map(function (csi) { return csi.string; }).join(',\n  ') + ']',
      JSON.stringify(styleDeps, undefined, 2),
      JSON.stringify(scriptDeps, undefined, 2),
      JSON.stringify(flatScriptDeps, undefined, 2)
    ]);
  };

  var preMeta = gulp.src(tabScripts, {base: './'})
    .pipe(filenames('firefox-deps'))
    .pipe(cache('meta'))
    .pipe(map(extractMetadata))
    .pipe(remember('meta'))
    .pipe(concat('meta.js'))
    .pipe(map(buildMetaScript));

  var webkitMeta = preMeta.pipe(clone())
    .pipe(map(function (code) {
      var data = JSON.parse(code.toString());
      return 'meta = {\n  contentScripts:' + data[0] +
        ',\n  styleDeps: ' + data[1] +
        ',\n  scriptDeps: ' + data[2] +
        "};\nif (/^Mac/.test(navigator.platform)) {\n  meta.styleDeps['scripts/keeper_scout.js'] = ['styles/mac.css'];\n}\n";
    }))
    .pipe(gulp.dest(outDir + '/chrome'))
    .pipe(gulp.dest(outDir + '/kifi.safariextension'));

  var firefoxMeta = preMeta.pipe(clone())
    .pipe(map(function (code) {
      var data = JSON.parse(code.toString());
      return 'exports.contentScripts =' + data[0] +
        ';\nexports.styleDeps = ' + data[1] +
        ';\nexports.scriptDeps = ' + data[2] +
        ';\nexports.flatScriptDeps = ' + data[3] +
        ";\nif (/^Mac/.test(require('sdk/system').platform)) {\n  exports.styleDeps['scripts/keeper_scout.js'] = ['styles/mac.css'];\n}\n";
    }))
    .pipe(gulp.dest(outDir + '/firefox/lib'));

  return es.merge(webkitMeta, firefoxMeta);
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
      json.updateLink = 'https://www.kifi.com/extensions/firefox/kifi' + (target === 'dev' ? '-dev' : '') + '.xpi';
      return json;
    }))
    .pipe(gulp.dest(outDir));

  var safariConfig = gulp.src('adapters/safari/Info.plist.json')
    .pipe(rename('kifi.safariextension/Info.plist'))
    .pipe(jeditor(function(json) {
      json.CFBundleShortVersionString = json.CFBundleVersion = version;

      if (!listed) {
        // updateURL is invalid for listed addons
        json.updateURL = 'https://www.kifi.com/extensions/firefox/kifi' + (target === 'dev' ? '-dev' : '') + '.update.rdf';
        json.id = 'kifi-unlisted@42go.com';
      }

      return json;
    }))
    .pipe(map(function (jsonBuffer) {
      var json = JSON.parse(jsonBuffer.toString());
      return plist.build(json);
    }))
    .pipe(gulp.dest(outDir));

  return es.merge(chromeConfig, safariConfig, firefoxConfig);
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

  var glob = 'kifi*\@42go.com-*.*.*.*update.rdf';

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
      // TODO: verify jpm version before using it
      // TODO(carlos): remove the line copying icons when https://bugzilla.mozilla.org/show_bug.cgi?id=1141839 and https://github.com/mozilla-jetpack/jpm/issues/341 are fixed
      // TODO(carlos): remove the line with sed when https://github.com/mozilla-jetpack/jpm/issues/409 is resolved
      (target === 'dev' ? 'cp icons/dev/kifi.??.png out/firefox/data/icons/ && ' : '') + '\
      cp icons/kifi.48.png out/firefox/icon.png && cp icons/kifi.64.png out/firefox/icon64.png && \
      cd ' + path.join(outDir + '/firefox') + ' && \
      jpm xpi && \
      sed -i ".bak" -e \'s/kifi.*\@42go.com/kifi\@42go.com/g\' ' + glob + ' && \
      node ../../bin/duplicateRdfDescription.js ' + glob + ' kifi.update.rdf ' + (listed ? 'listed' : 'unlisted') + ' && \
      sed -i ".bak" -e \'s/"urn:mozilla:kifi/"urn:mozilla:extension:kifi/g\' kifi.update.rdf && \
      sed -i ".bak" -e \'s/<em:maxVersion>.*<\\/em:maxVersion>/<em:maxVersion>48.0<\\/em:maxVersion>/g\' kifi.update.rdf && \
      cp *.xpi ../kifi.xpi && \
      cp kifi.update.rdf ../kifi.update.rdf && \
      cd - > /dev/null'
    ]));
});

gulp.task('watch', function () {
  livereload.listen(livereload.options.port);
  gulp.watch(
    [].concat(
      chromeAdapterFiles,
      safariAdapterFiles,
      firefoxAdapterFiles,
      sharedAdapterFiles,
      resourceFiles,
      rwsocketScript,
      backgroundScripts,
      localBackgroundScripts,
      htmlFiles),
    ['jsvalidate', 'scripts']);
  gulp.watch(styleFiles, ['styles']);
  gulp.watch(validateScripts, ['jsvalidate', 'meta']);
  gulp.watch(distFiles).on('change', reload);
});

gulp.task('package', function () {
  target = 'prod';
  runSequence('clean', 'jsvalidate', ['zip-chrome', 'xpi-firefox']);
});

gulp.task('package-listed', function () {
  target = 'prod';
  listed = true;
  runSequence('clean', 'jsvalidate', ['zip-chrome', 'xpi-firefox']);
})

gulp.task('package-dev', function () {
  target = 'dev';
  runSequence('clean', ['crx-chrome-dev', 'xpi-firefox']);
});

gulp.task('default', function () {
  runSequence('clean', 'jsvalidate', 'build', 'watch');
});
