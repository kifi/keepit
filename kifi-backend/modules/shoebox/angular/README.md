#1. Getting Started

###1.1. Install [node.js](http://nodejs.org/)

You need to [download](http://nodejs.org/download/) and install Node.js.


###1.2. Install node dependencies

After successfully installing Node.js, type `npm install` in terminal, and it will start downloading all package depenendencies for development under `node_module/`.

It will also automatically install [bower](http://bower.io/) and run `bower install` to install all run-time (client-side) package dependencies.

You can always do this step manually by typing `npm install -g bower` to install bower, the package manager, and `bower install` to install packages.


###1.3. Install `http-server`

Type `npm install -g http-server` to install http-server globally available as an executable command.

`http-server` is needed to serve static files to the browser on your local machine during development.

Visit project's [GitHub page](https://github.com/nodeapps/http-server) for more information.


#2. Running the app

###2.1. Run Grunt

Make sure that [Grunt-cli](http://gruntjs.com/getting-started) is already installed.

Run Grunt by typing `grunt` on your project directory where `gruntfile.js` is found.

It will run all default tasks and automatically go into watch mode to watch files for changes and run tasks automatically for you.

###2.2. Run `http-server`

Run an HTTP Server that serves files on your project directory, by typing `http-server`.

It will, by default, start and run a server listening on port 8080.

###2.3. Open a browser to `localhost:8080`.

Open a browser and type `localhost:8080` on the address bar to run the app.


#3. Start writing codes!

Start writing codes and make changes. It will automatically reload your browser for you when files are changed.


#4. Future works

###4.1. Tests are coming