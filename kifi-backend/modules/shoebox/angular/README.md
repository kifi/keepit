#1. Getting Started

###1.1. Install node.js

You need to [download](http://nodejs.org/download/) and install node.js.

###1.2. Install dev dependencies

After successfully installing node.js, type `npm install` in terminal, and it will start downloading all package depenendencies for development under `node_modules/`.

It will also automatically install [bower](http://bower.io/), the package manager, and run `bower install` to install all run-time (client-side) package dependencies.

You can always do this step manually by typing `npm install -g bower` and then `bower install`.

Install Gulp, our frontend build tool. `npm install -g gulp`.


#2. Running the app

###1.2. Run Gulp

Run Gulp by typing `gulp` in this directory.

It will run all default tasks and automatically go into watch mode to watch files for changes and run tasks automatically for you.

###2.2. Browse to `dev.ezkeep.com:8080`.

Visit `dev.ezkeep.com:8080` to run the app.


#3. Start writing code!

Edit the code! Your browser will reload changed stylesheets or the entire app, as appropriate, when you save files with changes.


#4. Things that need more documentation

##4.1. Testing
##4.2. High level architecture of the app
##4.3. How deploys work
