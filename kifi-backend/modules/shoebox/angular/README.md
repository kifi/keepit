#1. Getting Started

###1.1. Install node.js

You need to [download](http://nodejs.org/download/) and install node.js.

###1.2. Install node dependencies

After successfully installing node.js, type `npm install` in terminal, and it will start downloading all package depenendencies for development under `node_modules/`.

It will also automatically install [bower](http://bower.io/), the package manager, and run `bower install` to install all run-time (client-side) package dependencies.

You can always do this step manually by typing `npm install -g bower` and then `bower install`.


#2. Running the app

###2.1. Run `python server.py`

Run an HTTP Server that serves files on your project directory by typing `python server.py`.

It will, by default, start and run a server listening on port 8080.

###2.2. Run Grunt

Make sure that [grunt-cli](http://gruntjs.com/getting-started) is already installed.

Run Grunt by typing `grunt` on your project directory where `gruntfile.js` is found.

It will run all default tasks and automatically go into watch mode to watch files for changes and run tasks automatically for you.

###2.3. Browse to `dev.ezkeep.com:8080`.

Visit `dev.ezkeep.com:8080` to run the app.


#3. Start writing code!

Edit the code! Your browser will reload changed stylesheets or the entire app, as appropriate, when you save files with changes.


#4. Future work

###4.1. Tests are coming
