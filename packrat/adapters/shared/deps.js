!function(exports, meta) {
	exports.deps = function(paths, injected) {
		paths = typeof paths == 'string' ? [paths] : paths;
		var scripts = [], styles = {};
		for (var i = 0; i < paths.length; i++) {
			if (paths[i].substr(-3) == ".js") {
				scripts.push(paths[i]);
			} else {
				styles[paths[i]] = true;
			}
		}
		var notInjected = not.bind(null, injected || {});
		scripts = Object.keys(scripts.reduce(transitiveScriptDeps, {})).filter(notInjected);
		return {
			scripts: scripts,
			styles: Object.keys(scripts.reduce(styleDeps, styles)).filter(notInjected)};
	};

	function not(obj, key) {
		return !obj[key];
	}

	function transitiveScriptDeps(o, path) {
		var deps = meta.scriptDeps[path];
		if (deps) {
			deps.reduce(transitiveScriptDeps, o);
		}
		o[path] = true;
		return o;
	}

	function styleDeps(o, path) {
		var deps = meta.styleDeps[path];
		if (deps) {
			for (var i = 0; i < deps.length; i++) {
				o[deps[i]] = true;
			}
		}
		return o;
	}
}(this.exports || this, this.meta || require("./meta"));
