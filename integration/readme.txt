This folder contains api integration test code. It's using the frisby.js framework for node.js (http://frisbyjs.com/).
To make a new test simply drop a file in this folder with a name ending 'spec.js'.
The naming convention is <path prefix you are testing with '/' replaced with '-'>_spec.js
For an example on the naming and how the contents of a test look like see site-user_spec.js, which is supposed to test anything under the /site/user route.
These tests will be run every few minutes by jenkins, so it's not the place to test heavy lifting things like large imports.
