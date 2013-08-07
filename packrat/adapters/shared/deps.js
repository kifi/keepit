!function(exports, meta) {
	exports.deps = function(path, injected) {
		injected = injected || {};
		function notYetInjected(path) {
			return !injected[path];
		}

		if (path.substr(-4) === ".css") {
			return {scripts: [], styles: injected[path] ? [] : [path]};
		}

		var scripts = Object.keys([path].reduce(transitiveScriptDeps, {})).filter(notYetInjected);
		return {
			scripts: scripts,
			styles: Object.keys(scripts.reduce(styleDeps, {})).filter(notYetInjected)};
	};

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
